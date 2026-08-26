package com.citydrop.backend.deliveryOption;

import com.citydrop.backend.models.responses.DeliveryQuote;
import com.citydrop.backend.user.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

@RestController
public class DeliveryOptionController {

    private final DeliveryService deliveryService;
    private final UserService userService;

    public DeliveryOptionController(DeliveryService deliveryService, UserService userService) {
        this.deliveryService = deliveryService;
        this.userService = userService;
    }

    @GetMapping("/delivery-options")
    public List<DeliveryQuote> getDeliveryOptions(
            @RequestParam String destStreet,
            @RequestParam String destCity,
            @RequestParam String destState,
            @RequestParam String destZip,
            @RequestParam double packageWeight,
            Principal principal) {

        String destinationAddress = destStreet + ", " + destCity + ", " + destState + ", " + destZip;
        Integer userId = principal == null
                ? null
                : userService.findByUsername(principal.getName()).id();
        return deliveryService.getDeliveryOptions(destinationAddress, packageWeight, userId);
    }
}
