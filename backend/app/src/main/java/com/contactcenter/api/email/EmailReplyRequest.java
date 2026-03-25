package com.contactcenter.api.email;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO żądania wysłania odpowiedzi email.
 */
public record EmailReplyRequest(

        @NotBlank(message = "Treść odpowiedzi jest wymagana")
        String bodyHtml,

        @Size(max = 998, message = "Temat nie może być dłuższy niż 998 znaków")
        String subject
) {}
