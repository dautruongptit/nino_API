package com.app.nino.util;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MessageHelper {

    private final MessageSource messageSource;

    /**
     * Lay message theo key, tu dong lay Locale tu request hien tai.
     *
     * @param key  Key trong messages.properties (vi du: "event.not.found")
     * @param args Tham so thay the {0}, {1} trong message
     */
    public String get(String key, Object... args) {
        return messageSource.getMessage(
                key,
                args,
                LocaleContextHolder.getLocale()
        );
    }

    /**
     * Lay message khong co tham so.
     *
     * Vi du su dung trong Service:
     *   throw new ResourceNotFoundException(
     *       messageHelper.get("relative.not.found", id));
     */
    public String get(String key) {
        return get(key, (Object[]) null);
    }
}
