package com.citydrop.backend.chat;

import com.citydrop.backend.deliveryOption.AddressCannotBeGeocodedException;
import com.citydrop.backend.deliveryOption.DeliveryService;
import com.citydrop.backend.models.responses.DeliveryQuote;
import com.citydrop.backend.models.responses.OrderListResponse;
import com.citydrop.backend.models.responses.OrderObject;
import com.citydrop.backend.order.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private OpenAiClient openAiClient;

    @Mock
    private OrderService orderService;

    @Mock
    private DeliveryService deliveryService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void replyAnswersDirectlyWhenNoToolIsNeeded() {
        when(openAiClient.createCompletion(any(), any())).thenReturn(completionWithText("Hi, how can I help?"));

        ChatService chatService = new ChatService(openAiClient, orderService, deliveryService, objectMapper);
        ChatService.ChatOutcome outcome = chatService.reply(7, "hello", null);

        assertEquals("Hi, how can I help?", outcome.text());
        assertFalse(outcome.suggestCreateOrder());
        assertFalse(outcome.offerHumanHelp());
        verify(orderService, never()).getOrder(any(Integer.class), any(Integer.class));
    }

    // The tool call only ever carries an orderId -- the userId used to look
    // it up always comes from the authenticated caller (see ChatController),
    // never from the model. This is the regression test for that: even
    // though the model only supplied {"orderId": 42}, the service must call
    // orderService.getOrder with the *caller's* userId (7), not something
    // parsed out of the tool call.
    @Test
    void replyRunsGetOrderToolScopedToTheCallingUser() {
        when(openAiClient.createCompletion(any(), any()))
                .thenReturn(completionWithToolCall("call_1", "get_order", "{\"orderId\":42}"))
                .thenReturn(completionWithText("Order #42 is on its way."));
        when(orderService.getOrder(7, 42)).thenReturn(sampleOrder());

        ChatService chatService = new ChatService(openAiClient, orderService, deliveryService, objectMapper);
        ChatService.ChatOutcome outcome = chatService.reply(7, "how's my order 42?", null);

        assertEquals("Order #42 is on its way.", outcome.text());
        verify(orderService).getOrder(7, 42);
    }

    @Test
    void replyRunsListOrdersTool() {
        when(openAiClient.createCompletion(any(), any()))
                .thenReturn(completionWithToolCall("call_1", "list_orders", "{}"))
                .thenReturn(completionWithText("You have 2 active orders."));
        when(orderService.listOrder(7)).thenReturn(new OrderListResponse(List.of(), List.of()));

        ChatService chatService = new ChatService(openAiClient, orderService, deliveryService, objectMapper);
        ChatService.ChatOutcome outcome = chatService.reply(7, "what orders do I have?", null);

        assertEquals("You have 2 active orders.", outcome.text());
        verify(orderService).listOrder(7);
    }

    // Tool results have to make it back to OpenAI as role "tool" messages
    // carrying the same tool_call_id, or a real API call would reject the
    // second round outright.
    @Test
    @SuppressWarnings("unchecked")
    void toolResultIsSentBackWithMatchingCallId() {
        when(openAiClient.createCompletion(any(), any()))
                .thenReturn(completionWithToolCall("call_xyz", "list_orders", "{}"))
                .thenReturn(completionWithText("done"));
        when(orderService.listOrder(7)).thenReturn(new OrderListResponse(List.of(), List.of()));

        ChatService chatService = new ChatService(openAiClient, orderService, deliveryService, objectMapper);
        chatService.reply(7, "hi", null);

        ArgumentCaptor<List<Map<String, Object>>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(openAiClient, org.mockito.Mockito.times(2)).createCompletion(messagesCaptor.capture(), any());

        List<Map<String, Object>> secondRoundMessages = messagesCaptor.getAllValues().get(1);
        Map<String, Object> toolMessage = secondRoundMessages.get(secondRoundMessages.size() - 1);
        assertEquals("tool", toolMessage.get("role"));
        assertEquals("call_xyz", toolMessage.get("tool_call_id"));
    }

    @Test
    void replyRunsGetDeliveryQuoteTool() {
        when(openAiClient.createCompletion(any(), any()))
                .thenReturn(completionWithToolCall("call_1", "get_delivery_quote",
                        "{\"destination\":\"1398 Valencia St, San Francisco, CA\",\"packageWeightLbs\":5}"))
                .thenReturn(completionWithText("A robot delivery there would be $5.11."));
        when(deliveryService.getDeliveryOptions("1398 Valencia St, San Francisco, CA", 5.0))
                .thenReturn(List.of(new DeliveryQuote(
                        "1398 Valencia St, San Francisco, CA", 5.0, "ROBOT", 5.11, 18.3, 1, true)));

        ChatService chatService = new ChatService(openAiClient, orderService, deliveryService, objectMapper);
        ChatService.ChatOutcome outcome = chatService.reply(7, "how much to send 5 lbs to 1398 Valencia St?", null);

        assertEquals("A robot delivery there would be $5.11.", outcome.text());
        verify(deliveryService).getDeliveryOptions("1398 Valencia St, San Francisco, CA", 5.0);
    }

    // An out-of-range or ungeocodable address shouldn't blow up as a generic
    // "failed to look that up" -- the underlying exception's own message is
    // specific and useful enough to hand straight to the model.
    @Test
    void replyRelaysAddressExceptionMessageFromDeliveryQuoteTool() {
        when(openAiClient.createCompletion(any(), any()))
                .thenReturn(completionWithToolCall("call_1", "get_delivery_quote",
                        "{\"destination\":\"nowhere\",\"packageWeightLbs\":5}"))
                .thenReturn(completionWithText("I couldn't find that address."));
        when(deliveryService.getDeliveryOptions("nowhere", 5.0))
                .thenThrow(new AddressCannotBeGeocodedException());

        ChatService chatService = new ChatService(openAiClient, orderService, deliveryService, objectMapper);
        ChatService.ChatOutcome outcome = chatService.reply(7, "how much to send 5 lbs to nowhere?", null);

        assertEquals("I couldn't find that address.", outcome.text());
    }

    // A "suggest_create_order" call carries no data of its own -- it's a
    // pure signal, tracked separately from the tool loop's messages, so it
    // has to survive to the final response regardless of which round it
    // happened in or what other tools ran alongside it.
    @Test
    void replySignalsSuggestCreateOrderWhenTheToolIsCalled() {
        when(openAiClient.createCompletion(any(), any()))
                .thenReturn(completionWithToolCall("call_1", "suggest_create_order", "{}"))
                .thenReturn(completionWithText("I've pulled up the order form for you."));

        ChatService chatService = new ChatService(openAiClient, orderService, deliveryService, objectMapper);
        ChatService.ChatOutcome outcome = chatService.reply(7, "I want to send a package", null);

        assertEquals("I've pulled up the order form for you.", outcome.text());
        assertTrue(outcome.suggestCreateOrder());
        assertFalse(outcome.offerHumanHelp());
    }

    // Same signal pattern as suggest_create_order, for the model's other
    // no-op tool -- offerHumanHelp has to come back true regardless of what
    // else happened in the same reply.
    @Test
    void replySignalsOfferHumanHelpWhenFrustrationIsFlagged() {
        when(openAiClient.createCompletion(any(), any()))
                .thenReturn(completionWithToolCall("call_1", "flag_frustrated_user", "{}"))
                .thenReturn(completionWithText(
                        "I hear you -- this has been frustrating. I've flagged it for extra help."));

        ChatService chatService = new ChatService(openAiClient, orderService, deliveryService, objectMapper);
        ChatService.ChatOutcome outcome = chatService.reply(
                7, "this is the third time I've asked and nothing is working!!", null);

        assertEquals("I hear you -- this has been frustrating. I've flagged it for extra help.", outcome.text());
        assertTrue(outcome.offerHumanHelp());
        assertFalse(outcome.suggestCreateOrder());
    }

    // Same signal pattern as suggest_create_order/flag_frustrated_user, but
    // this one also carries data (which order) -- the id has to survive to
    // the final outcome, not just a boolean.
    @Test
    void replyCarriesOrderIdWhenSuggestCancelOrderIsCalled() {
        when(openAiClient.createCompletion(any(), any()))
                .thenReturn(completionWithToolCall("call_1", "suggest_cancel_order", "{\"orderId\":42}"))
                .thenReturn(completionWithText("I've pulled up a cancel confirmation for order #42."));

        ChatService chatService = new ChatService(openAiClient, orderService, deliveryService, objectMapper);
        ChatService.ChatOutcome outcome = chatService.reply(7, "cancel order 42", null);

        assertEquals("I've pulled up a cancel confirmation for order #42.", outcome.text());
        assertEquals(42, outcome.suggestCancelOrderId().intValue());
        assertFalse(outcome.suggestCreateOrder());
        assertFalse(outcome.offerHumanHelp());
    }

    // Guards against an infinite request loop if the model just keeps
    // calling tools forever instead of ever answering.
    @Test
    void replyGivesUpAfterTooManyToolRounds() {
        when(openAiClient.createCompletion(any(), any()))
                .thenReturn(completionWithToolCall("call_1", "list_orders", "{}"));
        when(orderService.listOrder(7)).thenReturn(new OrderListResponse(List.of(), List.of()));

        ChatService chatService = new ChatService(openAiClient, orderService, deliveryService, objectMapper);

        assertThrows(ChatUnavailableException.class, () -> chatService.reply(7, "hi", null));
    }

    private JsonNode completionWithText(String text) {
        return objectMapper.readTree("""
                {"choices":[{"message":{"role":"assistant","content":%s}}]}
                """.formatted(objectMapper.writeValueAsString(text)));
    }

    private JsonNode completionWithToolCall(String callId, String toolName, String argumentsJson) {
        Map<String, Object> functionCall = Map.of("name", toolName, "arguments", argumentsJson);
        Map<String, Object> toolCall = Map.of("id", callId, "type", "function", "function", functionCall);
        Map<String, Object> message = Map.of(
                "role", "assistant",
                "content", "",
                "tool_calls", List.of(toolCall));
        Map<String, Object> body = Map.of("choices", List.of(Map.of("message", message)));
        return objectMapper.valueToTree(body);
    }

    private OrderObject sampleOrder() {
        return new OrderObject(
                42, "123 Main St", 5.0, 12.50, 30.0, "ROBOT", 1,
                "PENDING_DROPOFF", "2026-08-19T12:00:00Z");
    }
}
