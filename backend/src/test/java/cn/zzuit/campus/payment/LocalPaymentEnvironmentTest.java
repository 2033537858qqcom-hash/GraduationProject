package cn.zzuit.campus.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;

class LocalPaymentEnvironmentTest {
    @TempDir Path directory;
    @Configuration(proxyBeanMethods=false) static class SettingsOnly {}
    private SpringApplication application() throws Exception {
        Path env=directory.resolve("payment.env");
        Files.writeString(env,"PAYMENT_ENABLED=true\nPAYMENT_CHANNEL=remote\nPAYMENT_REMOTE_URL=http://127.0.0.1:17285\nPAYMENT_REMOTE_TOKEN=local-fixture-only\n");
        var app=new SpringApplication(SettingsOnly.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setDefaultProperties(Map.of("CAMPUS_LOCAL_ENV_FILE",env.toString().replace('\\','/'),"spring.profiles.active","local"));
        return app;
    }
    @Test void localStartupLoadsPaymentSettingsWithoutPowerShellLauncher() throws Exception {
        try(var context=application().run()) {
            var env=context.getEnvironment();
            assertThat(env.getProperty("app.payment.enabled",Boolean.class)).isTrue();
            assertThat(env.getProperty("app.payment.channel")).isEqualTo("remote");
            assertThat(env.getProperty("app.payment.remote-url")).isEqualTo("http://127.0.0.1:17285");
            assertThat(env.getProperty("app.payment.remote-token")).isEqualTo("local-fixture-only");
        }
    }
    @Test void explicitSettingsCanStillDisablePayments() throws Exception {
        try(var context=application().run("--app.payment.enabled=false")) {
            assertThat(context.getEnvironment().getProperty("app.payment.enabled",Boolean.class)).isFalse();
        }
    }
}
