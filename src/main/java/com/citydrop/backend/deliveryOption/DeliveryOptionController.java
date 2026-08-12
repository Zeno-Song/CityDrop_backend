package com.citydrop.backend.deliveryOption;

import com.citydrop.backend.models.responses.DeliveryQuote;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DeliveryOptionController {

    private final DeliveryService deliveryService;

    public DeliveryOptionController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @GetMapping("/delivery-options")
    public DeliveryQuote[] getDeliveryOptions(
            @RequestParam String destStreet,
            @RequestParam String destCity,
            @RequestParam String destState,
            @RequestParam String destZip,
            @RequestParam double packageWeight) {

        String destinationAddress = destStreet + ", " + destCity + ", " + destState + ", " + destZip;
        return deliveryService.getDeliveryOptions(destinationAddress, packageWeight);
    }
}
