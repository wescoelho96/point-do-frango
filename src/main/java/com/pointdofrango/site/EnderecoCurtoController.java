package com.pointdofrango.site;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Endereço curto para a mensagem do WhatsApp: point-do-frango.onrender.com/pedir. */
@Controller
public class EnderecoCurtoController {

    @GetMapping("/pedir")
    public String pedir() {
        return "redirect:/pedido.html";
    }
}
