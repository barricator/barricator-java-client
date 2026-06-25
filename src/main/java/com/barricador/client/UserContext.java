package com.barricador.client;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The evaluation subject passed to {@code client.isEnabled(flag, user)}. Built via the fluent
 * {@link Builder}. The {@link #key()} is the stable identity used for deterministic percentage
 * rollouts and MUST be consistent across calls for a given subject.
 */
public final class UserContext {

    private final String key;
    private final String name;
    private final String email;
    private final String country;
    private final boolean anonymous;
    private final Map<String, Object> custom;

    private UserContext(Builder b) {
        this.key = b.key;
        this.name = b.name;
        this.email = b.email;
        this.country = b.country;
        this.anonymous = b.anonymous;
        this.custom = Collections.unmodifiableMap(new HashMap<>(b.custom));
    }

    public static Builder builder(String key) {
        return new Builder(key);
    }

    public String key() {
        return key;
    }

    /** Resolves an attribute by clause name, applying built-in precedence then custom attributes. */
    public Optional<Object> attribute(String attribute) {
        if (attribute == null) {
            return Optional.empty();
        }
        return switch (attribute) {
            case "key" -> Optional.ofNullable(key);
            case "name" -> Optional.ofNullable(name);
            case "email" -> Optional.ofNullable(email);
            case "country" -> Optional.ofNullable(country);
            case "anonymous" -> Optional.of(anonymous);
            default -> Optional.ofNullable(custom.get(attribute));
        };
    }

    public Map<String, Object> custom() {
        return custom;
    }

    public static final class Builder {
        private final String key;
        private String name;
        private String email;
        private String country;
        private boolean anonymous;
        private final Map<String, Object> custom = new HashMap<>();

        private Builder(String key) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("UserContext key must be non-empty");
            }
            this.key = key;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder email(String email) {
            this.email = email;
            return this;
        }

        public Builder country(String country) {
            this.country = country;
            return this;
        }

        public Builder anonymous(boolean anonymous) {
            this.anonymous = anonymous;
            return this;
        }

        public Builder custom(String attribute, Object value) {
            this.custom.put(attribute, value);
            return this;
        }

        public UserContext build() {
            return new UserContext(this);
        }
    }
}
