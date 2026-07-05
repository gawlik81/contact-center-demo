package com.contactcenter.domain.plugin.dto;

/**
 * Wpis konfiguracji instalacji pluginu z informacją o tym, czy klucz jest tajny (secret).
 *
 * <p>Klucze tajne (nazwa zawiera case-insensitive: {@code key}, {@code token}, {@code secret},
 * {@code password}) nigdy nie zwracają wartości — pole {@code value} jest wtedy {@code null}.
 * Klucze jawne mają odszyfrowaną wartość w polu {@code value}.
 *
 * @param key    nazwa klucza konfiguracyjnego
 * @param secret czy klucz jest uznany za tajny (wartość nie jest zwracana)
 * @param value  odszyfrowana wartość dla kluczy jawnych; {@code null} dla kluczy tajnych
 */
public record PluginConfigEntryDto(String key, boolean secret, String value) {}
