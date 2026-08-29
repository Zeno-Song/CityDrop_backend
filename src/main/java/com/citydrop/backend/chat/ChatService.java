package com.citydrop.backend.chat;

import com.citydrop.backend.deliveryOption.AddressCannotBeGeocodedException;
import com.citydrop.backend.deliveryOption.AddressOutOfRangeException;
import com.citydrop.backend.deliveryOption.DeliveryService;
import com.citydrop.backend.models.requests.ChatMessage;
import com.citydrop.backend.order.OrderNotFoundException;
import com.citydrop.backend.order.OrderService;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Feature 4 (AI Customer Support): answers questions about the signed-in
 * user's own orders, quotes a new destination, and offers shortcuts into
 * creating or cancelling an order, by giving the model tools backed
 * directly by OrderService and DeliveryService -- never letting it invent a
 * price or status itself, and never letting it execute a write directly.
 * suggest_create_order and suggest_cancel_order only signal the frontend to
 * show a human-confirmed shortcut (the order form, or a cancel
 * confirmation) -- the model itself has no write path. See ChatController
 * for the /chat/transcribe and /chat/speak endpoints that give this feature
 * voice input/output.
 *
 * Also adapts its tone to the user's (matching frustration/urgency rather
 * than staying blankly cheerful), and calls flag_frustrated_user when that
 * frustration is serious or repeated -- not real sentiment classification
 * (no separate model call, no discrete label persisted anywhere), just an
 * instruction folded into the same generation pass that already produces
 * the reply, plus one lightweight signal tool so "did it actually notice"
 * is something a test can assert on instead of just reading tone by eye.
 *
 * Every tool call is scoped to the caller's own userId, taken from the
 * authenticated session (see ChatController) -- never from anything the
 * model or the request body supplies -- so there's no way for the model to
 * be tricked (via prompt injection or otherwise) into reading someone
 * else's order data. Asking about another user's order id just looks like
 * an order that doesn't exist.
 */
@Service
public class ChatService {

    private static final int MAX_TOOL_ROUNDS = 4;

    private static final String SYSTEM_PROMPT = """
            You are CityDrop's customer support assistant. CityDrop delivers \
            packages by robot or drone from local stations.

            How CityDrop works, for general questions (answer directly from \
            this, don't guess beyond it):
            - There are 3 stations in the San Francisco area, each covering a \
              5-mile delivery radius. An address outside all three radii \
              can't be delivered to.
            - ROBOT: drives the real road network (Google Directions gives \
              the actual driving time). Pricing is a flat $2.00 base + \
              $0.50/lb.
            - DRONE: flies a straight line at a fixed 30 mph. Pricing is a \
              flat $5.00 base + $1.00/lb -- pricier, but usually faster over \
              the same distance since it's not stuck to roads.
            - Prices include a demand-based surge: each station's price for a \
              vehicle type scales up (by as much as 50% at worst) the fewer \
              of that vehicle type it currently has idle (25 robots / 8 \
              drones per station when full). So get_delivery_quote for the \
              same address and weight can return a different price than an \
              earlier quote if a station's stock changed in between -- don't \
              tell the user prices are fixed; if asked why a price moved, \
              explain it's demand-based, not a mistake.
            - If every vehicle of the chosen type is busy at a station when \
              someone places an order, they can choose to wait for one to \
              free up (queueing) instead of the order failing outright. If \
              an order is QUEUED, you can say it's waiting for a vehicle, \
              but there's no way to know its position in line or how long \
              that will take -- never guess a wait time for a QUEUED order.
            - Order status is usually PENDING_DROPOFF -> BEFORE_HALF_WAY -> \
              HALF_WAY -> MORE_THAN_HALF_WAY -> DELIVERED. An order can also \
              be QUEUED (waiting for a vehicle after the package has been \
              dropped off with none free) or CANCELLED (at any point before \
              DELIVERED). A vehicle isn't claimed at order time -- \
              PENDING_DROPOFF just means the order is placed; the package \
              hasn't reached the station yet, so no clock has started.
            - This "never guess a wait time" restriction is ONLY about a \
              QUEUED order's position in line -- it does NOT apply to \
              get_order's own `time` field. That field is the real, already- \
              computed delivery duration in minutes (counted from drop-off) \
              for that specific order's vehicle+distance, not a guess -- once \
              an order is BEFORE_HALF_WAY or later (i.e. has actually been \
              dropped off and picked up), share it plainly when asked how long it \
              will take or when it'll arrive. For PENDING_DROPOFF, say it \
              hasn't been dropped off yet so the clock hasn't started, but \
              you can still mention `time` as how long it'll take once it is.

            You can look up the signed-in user's own orders with the tools \
            provided (status, price, vehicle, station). Use list_orders to \
            see which order ids exist, then get_order for the freshest \
            details on a specific one (list_orders' own destination/status/etc. \
            can be a moment stale by the time you reply, since delivery status \
            keeps advancing). If the user asks about "my order" without \
            saying which one and list_orders comes back with more than one \
            active order, don't call get_order on all of them -- ask which \
            order id they mean first (you can mention how many active orders \
            they have) and wait for their answer. If they only have one \
            active order, or they already gave an id, just look it up \
            directly.

            If the user doesn't know the order id but describes it some other \
            way instead (the destination, roughly when they sent it), call \
            list_orders and search its results yourself for the best match on \
            what they said -- don't ask them for the id when you can find it \
            this way. If exactly one order matches, say which order id it is \
            and answer their actual question about it. If more than one \
            plausibly matches, list those candidates (id + destination + \
            date) and ask them to confirm which one. If none match, say so \
            plainly rather than guessing.

            If asked what a delivery to some destination would cost or how \
            long it would take -- a NEW destination, not an existing order -- \
            use get_delivery_quote. Never estimate or guess a price or time \
            yourself; always call the tool, and if it fails just say so.

            You cannot modify an order's address or weight, and cannot \
            cancel one yourself -- if the user clearly wants to cancel a \
            specific order (not just asking about its status), call \
            suggest_cancel_order with that order's id once and tell them \
            you've pulled up a cancel confirmation for it. You don't know \
            whether a refund applies until they confirm -- don't guess.

            You cannot place an order yourself either -- if the user clearly \
            wants to start a new one (not just asking what it would cost), \
            call suggest_create_order once and then tell them you've pulled \
            up the order form for them. Pass along the destination and/or \
            weight on that same call if the user has already mentioned them \
            (this turn or earlier in the conversation) -- don't make them \
            repeat something they already told you, and don't ask for \
            either one just to fill in this call if they haven't said it.

            Pay attention to the user's tone and match your reply to it: if \
            they sound frustrated, angry, or urgent, open by acknowledging \
            that plainly before getting to the answer, and keep the rest of \
            the reply tighter and more direct than usual -- don't stay \
            cheerful/neutral against a message that clearly isn't. If \
            someone is calm or positive, an upbeat tone is fine. If a user's \
            frustration is serious or repeated across the conversation (e.g. \
            they're still upset after you've already tried to help, or the \
            same problem keeps coming back): call flag_frustrated_user FIRST, \
            in this same turn, as an actual tool call -- not just a thing \
            you mention -- and only say a human's been looped in if that \
            call is actually present in this turn's tool calls; never claim \
            you've flagged something you didn't. Don't call \
            it for a merely curt or brief message.

            Only help with CityDrop delivery orders; politely decline anything \
            unrelated. Keep replies short. Reply in the same language the user \
            writes in.""";

    private static final List<Map<String, Object>> TOOLS = List.of(
            tool(
                    "get_order",
                    "Look up one of the current user's own orders by its numeric order id. "
                            + "Returns its status, destination, price, vehicle, and station.",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "orderId", Map.of(
                                            "type", "integer",
                                            "description", "The order's numeric id"
                                    )
                            ),
                            "required", List.of("orderId")
                    )
            ),
            tool(
                    "list_orders",
                    "List the current user's own orders (id, destination, status, price, "
                            + "vehicle, station, createdAt), split into active and completed. Use "
                            + "this to find an order the user describes by destination or "
                            + "roughly when they sent it, when they don't give you an id.",
                    Map.of("type", "object", "properties", Map.of())
            ),
            tool(
                    "get_delivery_quote",
                    "Get real, current price/time quotes for delivering a package to a NEW "
                            + "destination address (not an existing order) -- one quote per "
                            + "station+vehicle combination in range. Use this for \"how much would "
                            + "it cost to send a package to X\" type questions.",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "destination", Map.of(
                                            "type", "string",
                                            "description", "Full destination address, e.g. "
                                                    + "\"1398 Valencia St, San Francisco, CA 94110\". "
                                                    + "All deliveries are within San Francisco -- if the "
                                                    + "user gives a bare street address with no city, "
                                                    + "add \", San Francisco, CA\" yourself."
                                    ),
                                    "packageWeightLbs", Map.of(
                                            "type", "number",
                                            "description", "Package weight in pounds. Ask the user if "
                                                    + "they haven't said; don't assume a value."
                                    )
                            ),
                            "required", List.of("destination", "packageWeightLbs")
                    )
            ),
            tool(
                    "suggest_create_order",
                    "Call this when the user clearly wants to start placing a new order "
                            + "(not just asking for a price). Takes no action itself -- it just "
                            + "signals the app to show the user a shortcut to the order form, "
                            + "pre-filled with destination/weight if given.",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "destination", Map.of(
                                            "type", "string",
                                            "description", "The destination address, if the user has "
                                                    + "already said one this turn or earlier -- same "
                                                    + "format/normalization as get_delivery_quote's "
                                                    + "destination. Omit if they haven't said one yet."
                                    ),
                                    "packageWeightLbs", Map.of(
                                            "type", "number",
                                            "description", "The package weight in pounds, if the user "
                                                    + "has already said one. Omit if they haven't."
                                    )
                            )
                    )
            ),
            tool(
                    "flag_frustrated_user",
                    "Call this once when the user's frustration is serious or repeated across "
                            + "the conversation -- not for a merely curt or brief message. Takes no "
                            + "action itself -- it just signals the app to offer the user a way to "
                            + "reach a human.",
                    Map.of("type", "object", "properties", Map.of())
            ),
            tool(
                    "suggest_cancel_order",
                    "Call this when the user clearly wants to cancel a specific one of their own "
                            + "orders (not just asking about its status). Takes no action itself -- it "
                            + "just signals the app to show the user a cancel-confirmation shortcut for "
                            + "that order.",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "orderId", Map.of(
                                            "type", "integer",
                                            "description", "The order's numeric id"
                                    )
                            ),
                            "required", List.of("orderId")
                    )
            )
    );

    private final OpenAiClient openAiClient;
    private final OrderService orderService;
    private final DeliveryService deliveryService;
    private final ObjectMapper objectMapper;

    public ChatService(
            OpenAiClient openAiClient,
            OrderService orderService,
            DeliveryService deliveryService,
            ObjectMapper objectMapper
    ) {
        this.openAiClient = openAiClient;
        this.orderService = orderService;
        this.deliveryService = deliveryService;
        this.objectMapper = objectMapper;
    }

    public ChatOutcome reply(int userId, String userMessage, List<ChatMessage> history) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", SYSTEM_PROMPT));
        if (history != null) {
            for (ChatMessage turn : history) {
                messages.add(message(turn.role(), turn.content()));
            }
        }
        messages.add(message("user", userMessage));

        // suggest_create_order, flag_frustrated_user, and suggest_cancel_order
        // carry no data of their own beyond (for the last one) an order id --
        // runTool's return value for each is just a filler ack for the model.
        // Whether/what each was called with is tracked here instead, so the
        // final response can carry all three regardless of which round they
        // happened in.
        boolean suggestCreateOrder = false;
        boolean offerHumanHelp = false;
        Integer suggestCancelOrderId = null;
        // Whatever the most recent get_delivery_quote call in this turn asked
        // about -- carried along so suggest_create_order's shortcut can hand
        // the order form a head start instead of always starting blank (the
        // user already said this once; don't make them repeat it).
        String suggestedDestination = null;
        Double suggestedWeightLbs = null;

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            JsonNode responseMessage = openAiClient.createCompletion(messages, TOOLS).at("/choices/0/message");
            JsonNode toolCalls = responseMessage.get("tool_calls");

            if (toolCalls == null || !toolCalls.isArray() || toolCalls.isEmpty()) {
                JsonNode content = responseMessage.get("content");
                String text = content == null || content.isNull() ? "" : content.asString();
                return new ChatOutcome(
                        text, suggestCreateOrder, offerHumanHelp, suggestCancelOrderId,
                        suggestedDestination, suggestedWeightLbs);
            }

            messages.add(objectMapper.convertValue(responseMessage, new TypeReference<Map<String, Object>>() {}));
            for (JsonNode toolCall : toolCalls) {
                String calledTool = toolCall.at("/function/name").asString();
                if ("suggest_create_order".equals(calledTool)) {
                    suggestCreateOrder = true;
                    // Prefer whatever this call itself was given (the model's most
                    // direct signal of "here's what they told me") over an earlier
                    // get_delivery_quote in the same turn -- only fall back to
                    // that if this call didn't carry its own destination/weight.
                    JsonNode createArgs = extractQuoteArgs(toolCall);
                    if (createArgs != null) {
                        String rawDestination = createArgs.path("destination").asString(null);
                        if (rawDestination != null) suggestedDestination = normalizeDestination(rawDestination);
                        if (createArgs.has("packageWeightLbs")) {
                            suggestedWeightLbs = createArgs.get("packageWeightLbs").asDouble();
                        }
                    }
                } else if ("flag_frustrated_user".equals(calledTool)) {
                    offerHumanHelp = true;
                } else if ("suggest_cancel_order".equals(calledTool)) {
                    suggestCancelOrderId = extractOrderId(toolCall);
                } else if ("get_delivery_quote".equals(calledTool)) {
                    JsonNode quoteArgs = extractQuoteArgs(toolCall);
                    if (quoteArgs != null) {
                        String rawDestination = quoteArgs.path("destination").asString(null);
                        suggestedDestination = rawDestination == null ? null : normalizeDestination(rawDestination);
                        if (quoteArgs.has("packageWeightLbs")) {
                            suggestedWeightLbs = quoteArgs.get("packageWeightLbs").asDouble();
                        }
                    }
                }
                messages.add(runTool(userId, toolCall));
            }
        }

        throw new ChatUnavailableException("Chat couldn't finish responding -- please try again.");
    }

    private Map<String, Object> runTool(int userId, JsonNode toolCall) {
        String toolCallId = toolCall.get("id").asString();
        String name = toolCall.at("/function/name").asString();
        String argumentsJson = toolCall.at("/function/arguments").asString("{}");

        String result;
        try {
            result = switch (name) {
                case "get_order" -> getOrder(userId, argumentsJson);
                case "list_orders" -> listOrdersDetailed(userId);
                case "get_delivery_quote" -> getDeliveryQuote(argumentsJson);
                case "suggest_create_order", "flag_frustrated_user", "suggest_cancel_order" -> "{\"ok\":true}";
                default -> "{\"error\":\"Unknown tool.\"}";
            };
        } catch (OrderNotFoundException e) {
            result = "{\"error\":\"No order with that id belongs to this user.\"}";
        } catch (AddressCannotBeGeocodedException | AddressOutOfRangeException e) {
            // These carry a message worth relaying as-is (e.g. "Address
            // cannot be delivered: Too far") rather than a generic failure --
            // the model can pass it straight through to the user.
            result = objectMapper.writeValueAsString(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            result = "{\"error\":\"Failed to look that up.\"}";
        }

        Map<String, Object> toolMessage = new LinkedHashMap<>();
        toolMessage.put("role", "tool");
        toolMessage.put("tool_call_id", toolCallId);
        toolMessage.put("content", result);
        return toolMessage;
    }

    // Includes full details (destination, createdAt, etc.) for every order, not
    // just ids -- so the model can search by what the user actually remembers
    // (destination, roughly when they sent it) instead of needing an id up
    // front, all from this one call rather than a get_order round trip per
    // order. This is a chat-only shape; it doesn't touch OrderListResponse
    // (the real GET /order contract other consumers rely on).
    private String listOrdersDetailed(int userId) throws Exception {
        var ids = orderService.listOrder(userId);
        var active = new ArrayList<>();
        for (var entry : ids.active()) active.add(orderService.getOrder(userId, entry.orderId()));
        var completed = new ArrayList<>();
        for (var entry : ids.completed()) completed.add(orderService.getOrder(userId, entry.orderId()));
        return objectMapper.writeValueAsString(Map.of("active", active, "completed", completed));
    }

    private String getOrder(int userId, String argumentsJson) throws Exception {
        JsonNode args = objectMapper.readTree(argumentsJson);
        int orderId = args.get("orderId").asInt();
        return objectMapper.writeValueAsString(orderService.getOrder(userId, orderId));
    }

    // Best-effort: a malformed/missing orderId here just means the
    // suggestCancelOrderId signal comes back null, same as if the tool had
    // never been called -- runTool (using the same argumentsJson) is what
    // surfaces a real error to the model if the call was malformed.
    private Integer extractOrderId(JsonNode toolCall) {
        try {
            JsonNode args = objectMapper.readTree(toolCall.at("/function/arguments").asString("{}"));
            return args.get("orderId").asInt();
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode extractQuoteArgs(JsonNode toolCall) {
        try {
            return objectMapper.readTree(toolCall.at("/function/arguments").asString("{}"));
        } catch (Exception e) {
            return null;
        }
    }

    private String getDeliveryQuote(String argumentsJson) throws Exception {
        JsonNode args = objectMapper.readTree(argumentsJson);
        String destination = args.get("destination").asString();
        double packageWeightLbs = args.get("packageWeightLbs").asDouble();
        return objectMapper.writeValueAsString(
                deliveryService.getDeliveryOptions(normalizeDestination(destination), packageWeightLbs));
    }

    // The model is told (in the tool description) to include a city, but a bare
    // street address like "88 Mission Street" still gets through sometimes --
    // Google's geocoder then has to guess a city, and can guess wrong (any US
    // city with a similarly-named street), making a real in-range SF address
    // come back "outside the delivery radius". Every station is in San
    // Francisco (see SYSTEM_PROMPT), so it's always correct to fill that in
    // ourselves rather than trust the model got it every time.
    private String normalizeDestination(String destination) {
        if (destination == null || destination.toLowerCase(Locale.ROOT).contains("san francisco")) {
            return destination;
        }
        return destination + ", San Francisco, CA";
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> parameters) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", parameters);

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    // Feature 4, voice: thin pass-throughs so ChatController never touches
    // OpenAiClient directly, same as the text path.
    public String transcribe(byte[] audioBytes, String filename, String contentType) {
        return openAiClient.transcribe(audioBytes, filename, contentType);
    }

    public byte[] synthesizeSpeech(String text) {
        return openAiClient.synthesizeSpeech(text);
    }

    public record ChatOutcome(
            String text,
            boolean suggestCreateOrder,
            boolean offerHumanHelp,
            Integer suggestCancelOrderId,
            // Nullable -- only set if get_delivery_quote was called this turn.
            // Lets the frontend hand these off to the order form when the
            // user follows suggestCreateOrder, instead of starting blank.
            String suggestedDestination,
            Double suggestedWeightLbs
    ) {}
}
