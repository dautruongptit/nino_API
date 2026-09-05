package com.app.nino.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

@Configuration
public class MessageConfig {

    /**
     * MessageSource doc file tu classpath:i18n/messages*.properties.
     * Encoding UTF-8 de hien thi ky tu tieng Viet dung.
     */
    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource source =
                new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setDefaultLocale(Locale.forLanguageTag("vi"));
        source.setCacheSeconds(3600); // cache 1h, -1 de reload luc dev
        return source;
    }

    /**
     * LocaleResolver doc ngon ngu tu header "Accept-Language".
     * Vi du: Accept-Language: vi  =>  Tieng Viet
     *         Accept-Language: en  =>  English
     * Mac dinh: vi neu header khong co hoac khong hop le.
     */
    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(
                List.of(Locale.forLanguageTag("vi"), Locale.ENGLISH));
        resolver.setDefaultLocale(Locale.forLanguageTag("vi"));
        return resolver;
    }
}
