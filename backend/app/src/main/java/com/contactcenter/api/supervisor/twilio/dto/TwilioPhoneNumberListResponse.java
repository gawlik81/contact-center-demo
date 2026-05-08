package com.contactcenter.api.supervisor.twilio.dto;

import java.util.List;

public record TwilioPhoneNumberListResponse(List<TwilioPhoneNumberDto> phoneNumbers) {}
