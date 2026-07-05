package com.acme.contactcenter.plugin.googlelookup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimalny, samodzielny parser JSON (bez żadnej zależności).
 *
 * <p>{@code plugin-sdk} celowo nie dostarcza żadnej biblioteki JSON (zero zależności poza
 * JDK — patrz {@code pom.xml} modułu SDK), a ten przykład celowo nie dodaje zewnętrznej
 * zależności, żeby zademonstrować, że plugin nie musi pakować niczego poza własnym kodem.
 * Produkcyjny plugin z bardziej złożonymi potrzebami JSON powinien dołączyć sprawdzoną
 * bibliotekę (np. Gson, zshade'owaną do własnego JAR-a, z relokacją pakietów) — żadna z
 * popularnych bibliotek JSON nie odwołuje się do API zablokowanych przez statyczny skan
 * bytecode platformy (zob. documentation/10-plugin-development.md, §7).
 *
 * <p>Wspiera podzbiór JSON wystarczający do odczytu odpowiedzi Google Custom Search API:
 * obiekty, tablice, stringi (z escapingiem), liczby, {@code true}/{@code false}/{@code null}.
 * Nie jest to parser zgodny ze specyfikacją RFC 8259 w 100% (np. nie waliduje duplikatów
 * kluczy) — wystarczający dla tego, jednego, znanego kształtu odpowiedzi.
 */
final class MinimalJson {

    private final String src;
    private int pos;

    private MinimalJson(String src) {
        this.src = src;
    }

    static Object parse(String json) {
        MinimalJson parser = new MinimalJson(json);
        parser.skipWhitespace();
        return parser.parseValue();
    }

    private Object parseValue() {
        char c = peek();
        return switch (c) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't', 'f' -> parseBoolean();
            case 'n' -> parseNull();
            default -> parseNumber();
        };
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            map.put(key, parseValue());
            skipWhitespace();
            char next = src.charAt(pos++);
            if (next == '}') {
                break;
            }
            if (next != ',') {
                throw new IllegalArgumentException("Nieprawidłowy JSON (oczekiwano ',' lub '}') na pozycji " + pos);
            }
        }
        return map;
    }

    private List<Object> parseArray() {
        List<Object> list = new ArrayList<>();
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(parseValue());
            skipWhitespace();
            char next = src.charAt(pos++);
            if (next == ']') {
                break;
            }
            if (next != ',') {
                throw new IllegalArgumentException("Nieprawidłowy JSON (oczekiwano ',' lub ']') na pozycji " + pos);
            }
        }
        return list;
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = src.charAt(pos++);
            if (c == '"') {
                break;
            }
            if (c == '\\') {
                char escaped = src.charAt(pos++);
                sb.append(switch (escaped) {
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'u' -> parseUnicodeEscape();
                    default -> escaped;
                });
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private char parseUnicodeEscape() {
        String hex = src.substring(pos, pos + 4);
        pos += 4;
        return (char) Integer.parseInt(hex, 16);
    }

    private Boolean parseBoolean() {
        if (src.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Nieprawidłowa wartość logiczna na pozycji " + pos);
    }

    private Object parseNull() {
        if (src.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw new IllegalArgumentException("Nieprawidłowa wartość 'null' na pozycji " + pos);
    }

    private Double parseNumber() {
        int start = pos;
        while (pos < src.length() && "-+.0123456789eE".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        return Double.parseDouble(src.substring(start, pos));
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        return src.charAt(pos);
    }

    private void expect(char c) {
        if (src.charAt(pos) != c) {
            throw new IllegalArgumentException("Oczekiwano '" + c + "' na pozycji " + pos);
        }
        pos++;
    }
}
