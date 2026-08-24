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
              4-mile delivery radius. An address outside all three radii \
              can't be delivered to.
            - ROBOT: drives the real road network (Google Directions gives \
              the actual driving time). Pricing is a flat $2.00 base + \
              $0.50/lb.
            - DRONE: flies a straight line at a fixed 30 mph. Pricing is a \
              flat $5.00 base + $1.00/lb -- pricier, but usually faster over \
              the same distance since it's not stuck to roads.
            - Prices are flat -- get_delivery_quote for the same address and \
              weight always returns the same numbers; there's no demand-based \
              surge right now.
            - If every vehicle of the chosen type is busy at a station when \
              someone places an order, they can choose to wait for one to \
              free up (queueing) instead of the order failing outright. If \
              an order is QUEUED, you can say it's waiting for a vehicle, \
              but there's no way to know its position in line or how long \
              that will take -- never guess a wait time.
            - Order status is usually PENDING_DROPOFF -> AT_STATION -> \
              BEFORE_HALF_WAY -> HALF_WAY -> MORE_THAN_HALF_WAY -> DELIVERED. \
              An order can also be QUEUED (waiting for a vehicle, before \
              PENDING_DROPOFF) or CANCELLED (at any point before DELIVERED).

            You can look up the signed-in user's own orders with the tools \
            provided (status, price, vehicle, station). Use list_orders to \
            see which order ids exist, then get_order for details on a \
            specific one.

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
            up the order form for them.

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
                    "List the current user's own order ids, split into active and completed.",
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
                                                    + "\"1398 Valencia St, San Francisco, CA 94110\""
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
                            + "signals the app to show the user a shortcut to the order form.",
                    Map.of("type", "object", "properties", Map.of())
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

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            JsonNode responseMessage = openAiClient.createCompletion(messages, TOOLS).at("/choices/0/message");
            JsonNode toolCalls = responseMessage.get("tool_calls");

            if (toolCalls == null || !toolCalls.isArray() || toolCalls.isEmpty()) {
                JsonNode content = responseMessage.get("content");
                String text = content == null || content.isNull() ? "" : content.asString();
                return new ChatOutcome(text, suggestCreateOrder, offerHumanHelp, suggestCancelOrderId);
            }

            messages.add(objectMapper.convertValue(responseMessage, new TypeReference<Map<String, Object>>() {}));
            for (JsonNode toolCall : toolCalls) {
                String calledTool = toolCall.at("/function/name").asString();
                if ("suggest_create_order".equals(calledTool)) {
                    suggestCreateOrder = true;
                } else if ("flag_frustrated_user".equals(calledTool)) {
                    offerHumanHelp = true;
                } else if ("suggest_cancel_order".equals(calledTool)) {
                    suggestCancelOrderId = extractOrderId(toolCall);
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
                case "list_orders" -> objectMapper.writeValueAsString(orderService.listOrder(userId));
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

    private String getDeliveryQuote(String argumentsJson) throws Exception {
        JsonNode args = objectMapper.readTree(argumentsJson);
        String destination = args.get("destination").asString();
        double packageWeightLbs = args.get("packageWeightLbs").asDouble();
        return objectMapper.writeValueAsString(
                deliveryService.getDeliveryOptions(destination, packageWeightLbs));
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
            Integer suggestCancelOrderId
    ) {}
}
