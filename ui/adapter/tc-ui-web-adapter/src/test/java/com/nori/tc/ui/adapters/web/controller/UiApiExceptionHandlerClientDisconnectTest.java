package com.nori.tc.ui.adapters.web.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nori.tc.ui.adapters.web.controller.support.UiApiExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 클라이언트가 응답 도중 연결을 끊은 경우가 ERROR 로 기록되지 않는지 검증합니다.
 *
 * <p>브라우저가 탭을 닫거나 요청을 취소하면 응답 write 가 {@link IOException} 으로 실패하고
 * Spring 이 이를 {@link HttpMessageNotWritableException}(500) 으로 감싼다. 서버가 잘못한 것이
 * 없는데도 ERROR 로 남으면 실제 장애와 구분되지 않고, "런타임 ERROR 0" 게이트를 오염시킨다
 * (2026-08-10 로컬 실측 — Playwright 순회 중 23 건이 이 형태로 쌓였다).</p>
 *
 * <p>서버 자신의 직렬화 실패(원인 사슬에 {@link IOException} 이 없는 경우)는 진짜 결함이므로
 * ERROR 를 유지해야 한다 — 그 구분까지 함께 검증한다.</p>
 */
class UiApiExceptionHandlerClientDisconnectTest {

    private static final String HANDLER_LOGGER = UiApiExceptionHandler.class.getName();

    private MockMvc mockMvc;
    private ListAppender<ILoggingEvent> logAppender;
    private ch.qos.logback.classic.Logger handlerLogger;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new UiApiExceptionHandler(UiControllerTestMessageSource.create()))
                .build();

        final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        handlerLogger = loggerContext.getLogger(HANDLER_LOGGER);
        logAppender = new ListAppender<>();
        logAppender.setContext(loggerContext);
        logAppender.start();
        handlerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    @DisplayName("클라이언트 연결 끊김은 ERROR 가 아니라 WARN 으로 기록됩니다")
    void clientDisconnectIsLoggedAsWarn() throws Exception {
        mockMvc.perform(get("/probe/client-disconnect")).andReturn();

        final List<ILoggingEvent> events = List.copyOf(logAppender.list);
        assertThat(events).isNotEmpty();
        assertThat(events)
                .as("클라이언트 귀책인 연결 끊김을 ERROR 로 남기면 실제 장애와 구분되지 않는다")
                .noneMatch(event -> event.getLevel() == Level.ERROR);
        assertThat(events)
                .as("무시하지는 않는다 — 어떤 요청에서 끊겼는지는 WARN 으로 남아야 한다")
                .anyMatch(event -> event.getLevel() == Level.WARN);
    }

    @Test
    @DisplayName("서버 자신의 직렬화 실패는 ERROR 를 유지합니다")
    void serverSideSerializationFailureStaysError() throws Exception {
        mockMvc.perform(get("/probe/serialization-failure")).andReturn();

        assertThat(List.copyOf(logAppender.list))
                .as("원인 사슬에 IOException 이 없으면 서버 결함이므로 ERROR 여야 한다")
                .anyMatch(event -> event.getLevel() == Level.ERROR);
    }

    @RestController
    static class ProbeController {

        /** 브라우저가 응답 수신 도중 연결을 끊은 형태 — 원인 사슬 끝이 {@link IOException} 이다. */
        @GetMapping("/probe/client-disconnect")
        String clientDisconnect() {
            throw new HttpMessageNotWritableException(
                    "Could not write JSON: ServletOutputStream failed to write",
                    new IOException("Connection reset by peer")
            );
        }

        /** 서버가 직렬화할 수 없는 값을 반환한 형태 — 연결과 무관한 진짜 결함이다. */
        @GetMapping("/probe/serialization-failure")
        String serializationFailure() {
            throw new HttpMessageNotWritableException(
                    "No serializer found for class",
                    new IllegalStateException("no serializer")
            );
        }
    }
}
