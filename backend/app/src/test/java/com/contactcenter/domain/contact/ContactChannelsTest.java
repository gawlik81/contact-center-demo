package com.contactcenter.domain.contact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ContactChannels - jedno miejsce mapowania SOCIAL_* -> SOCIAL (BE-155)")
class ContactChannelsTest {

    @ParameterizedTest
    @CsvSource({"SOCIAL_FACEBOOK,SOCIAL", "SOCIAL_INSTAGRAM,SOCIAL", "SOCIAL_WHATSAPP,SOCIAL", "SOCIAL,SOCIAL",
            "PHONE,PHONE", "EMAIL,EMAIL", "CHAT,CHAT"})
    void mapsToAgentDesktopType(String channel, String expected) {
        assertThat(ContactChannels.toAgentDesktopType(channel)).isEqualTo(expected);
    }

    @Test
    void nullMapsToPhone() {
        assertThat(ContactChannels.toAgentDesktopType(null)).isEqualTo("PHONE");
        assertThat(ContactChannels.isSocial(null)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"SOCIAL_FACEBOOK,true", "SOCIAL_INSTAGRAM,true", "SOCIAL_WHATSAPP,true", "PHONE,false", "EMAIL,false"})
    void isSocial(String channel, boolean expected) {
        assertThat(ContactChannels.isSocial(channel)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"EMAIL,true", "CHAT,true", "SOCIAL_FACEBOOK,true", "SOCIAL_INSTAGRAM,true", "SOCIAL_WHATSAPP,true",
            "PHONE,false", "VOICEBOT,false"})
    void isAsync(String channel, boolean expected) {
        assertThat(ContactChannels.isAsync(channel)).isEqualTo(expected);
    }

    @Test
    void nullIsNotAsync() {
        assertThat(ContactChannels.isAsync(null)).isFalse();
    }
}
