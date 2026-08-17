package com.citydrop.backend.order;

import com.citydrop.backend.db.entities.UserEntity;
import com.citydrop.backend.enums.OrderStatus;
import com.citydrop.backend.models.requests.SubmissionObject;
import com.citydrop.backend.models.responses.CancelOrderResponse;
import com.citydrop.backend.models.responses.OrderListResponse;
import com.citydrop.backend.models.responses.OrderObject;
import com.citydrop.backend.user.UserService;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/order")
public class OrderController {

    private final OrderService orderService;
    private final UserService userService;

    public OrderController(OrderService orderService, UserService userService) {
        this.orderService = orderService;
        this.userService = userService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderObject submitOrder(
            @RequestBody SubmissionObject order,
            Principal principal) {
        int userId = getAuthenticatedUserId(principal);
        return orderService.submitOrder(userId, order);
    }

    @GetMapping
    public OrderListResponse listOrders(Principal principal) {
        int userId = getAuthenticatedUserId(principal);
        return orderService.listOrder(userId);
    }

    @GetMapping("/{id}")
    public OrderObject getOrder(
            @PathVariable("id") int orderId,
            Principal principal) {
        int userId = getAuthenticatedUserId(principal);
        return orderService.getOrder(userId, orderId);
    }

    @PatchMapping(value = "/{id}/dropped-off", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public String markDroppedOff(
            @PathVariable("id") int orderId,
            Principal principal) {
        int userId = getAuthenticatedUserId(principal);
        return orderService.updateStatus(userId, orderId, OrderStatus.AT_STATION.name());
    }

    @PostMapping(value = "/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public CancelOrderResponse cancelOrder(
            @PathVariable("id") int orderId,
            Principal principal) {
        int userId = getAuthenticatedUserId(principal);
        return orderService.cancelOrder(userId, orderId);
    }

    private int getAuthenticatedUserId(Principal principal) {
        UserEntity user =
                userService.findByUsername(principal.getName());
        return user.id();
    }
}
