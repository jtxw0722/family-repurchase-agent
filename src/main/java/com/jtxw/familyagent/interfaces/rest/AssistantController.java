package com.jtxw.familyagent.interfaces.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jtxw.familyagent.application.AssistantApplicationService;
import com.jtxw.familyagent.infrastructure.llm.LlmException;
import com.jtxw.familyagent.infrastructure.llm.LlmRequest;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * @Author: jtxw
 * @Date: 2026/11/19 17:52:58
 * @Description: 网页聊天 REST 入口，负责校验对话请求并返回完整回复或逐行流式事件
 */
@RestController
public class AssistantController {
    /** 网页聊天日志记录器，仅记录模型异常摘要。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(AssistantController.class);

    /** 每条用户消息上限，单位为字符，包含历史问题。 */
    private static final int MAX_QUESTION_LENGTH = 2000;

    /** 每条回传历史回复的上限，单位为字符。 */
    private static final int MAX_HISTORY_ANSWER_LENGTH = 4000;

    /** 单次请求允许附带的完整历史问答轮数上限。 */
    private static final int MAX_HISTORY_TURNS = 4;

    /** 模型配置缺失时返回给网页的提示，固定且不含实际配置值。 */
    private static final String MODEL_NOT_CONFIGURED = "聊天模型尚未配置，请在服务端设置模型地址、名称和 API Key。";

    /** 模型调用失败时统一返回给网页的提示，固定且不含供应商响应正文。 */
    private static final String MODEL_ERROR = "聊天模型暂时无法回复，请稍后重试。";

    /** 网页聊天应用服务，非空。 */
    private final AssistantApplicationService assistant;

    /** 将流式事件编码为单行 UTF-8 JSON 的序列化器，非空。 */
    private final ObjectWriter eventWriter;

    /**
     * 注入对话应用服务。
     *
     * @param assistant 对话应用服务
     * @param objectMapper 流式事件序列化器
     */
    public AssistantController(AssistantApplicationService assistant, ObjectMapper objectMapper) {
        this.assistant = assistant;
        this.eventWriter = objectMapper.writer().without(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * 提交一条聊天消息。服务会向所配置的模型发送当前消息和近期上下文。
     *
     * @param request 当前消息及可选历史轮次
     * @return 包含模型回复的响应，尚未配置模型时返回 503
     * @throws LlmException 配置的模型服务不可用或返回无效内容时抛出
     */
    @PostMapping("/api/assistant/chat")
    public ResponseEntity<Map<String, String>> chat(@Valid @RequestBody ChatRequest request) {
        if (!assistant.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", MODEL_NOT_CONFIGURED));
        }
        String answer = assistant.chat(request.message().trim(), history(request));
        return ResponseEntity.ok(Map.of("answer", answer));
    }

    /**
     * 接收聊天消息并立即刷新每个回复片段；正常结束时发送完成事件。
     *
     * @param request 当前消息及可选历史轮次
     * @param response 当前 HTTP 响应，向客户端写入 UTF-8 逐行 JSON；模型未配置时返回 503
     * @throws IOException 无法打开 HTTP 响应流时抛出
     */
    @PostMapping("/api/assistant/chat/stream")
    public void streamChat(@Valid @RequestBody ChatRequest request, HttpServletResponse response)
            throws IOException {
        ServletOutputStream output = response.getOutputStream();
        if (!assistant.isConfigured()) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            writeEvent(output, Map.of("error", MODEL_NOT_CONFIGURED));
            return;
        }
        response.setContentType(MediaType.APPLICATION_NDJSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        try {
            try {
                assistant.streamChat(request.message().trim(), history(request),
                        delta -> writeEvent(output, Map.of("delta", delta)));
                writeEvent(output, Map.of("done", true));
            } catch (LlmException exception) {
                LOGGER.warn("网页聊天流式模型调用失败，reason={}", exception.getMessage());
                writeEvent(output, Map.of("error", MODEL_ERROR));
            }
        } catch (UncheckedIOException exception) {
            // 浏览器关闭连接后停止转发，模型响应由客户端适配器关闭。
            LOGGER.debug("网页聊天响应流已关闭，cause={}", exception.getCause().getClass().getSimpleName());
        }
    }

    /**
     * 将网页中的历史轮次转换为模型请求需要的问答结构。
     *
     * @param request 已校验的聊天请求
     * @return 历史轮次列表；省略历史时返回空列表
     */
    private List<LlmRequest.ChatTurn> history(ChatRequest request) {
        return request.history() == null ? List.of() : request.history().stream()
                .map(turn -> new LlmRequest.ChatTurn(turn.question(), turn.answer()))
                .toList();
    }

    /**
     * 写入并刷新一行 JSON，以便网页在下一段模型回复生成前就能处理当前事件。
     *
     * @param output HTTP 响应流，由 Servlet 容器关闭
     * @param event 片段、正常结束或失败事件，非空
     * @throws UncheckedIOException 浏览器断开或响应写入失败时抛出
     */
    private void writeEvent(ServletOutputStream output, Map<String, ?> event) {
        try {
            output.write(eventWriter.writeValueAsBytes(event));
            output.write('\n');
            output.flush();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * 将模型连接或响应异常转换为聊天页可用的错误信息。
     *
     * @param exception 供应商客户端提供的脱敏异常信息
     * @return HTTP 502 及面向用户的提示
     */
    @ExceptionHandler(LlmException.class)
    public ResponseEntity<Map<String, String>> handleModelError(LlmException exception) {
        LOGGER.warn("网页聊天模型调用失败，reason={}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", MODEL_ERROR));
    }

    /**
     * 将无效问题或超长会话上下文转换为可读错误。
     *
     * @return HTTP 400 及聊天输入限制提示
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleInvalidRequest() {
        return ResponseEntity.badRequest().body(Map.of("error", "消息为空或对话内容超出长度限制。"));
    }

    /**
     * @Author: jtxw
     * @Date: 2026/11/19 17:52:58
     * @Description: 网页聊天的 REST 请求，承载当前问题和有上限的历史问答
     * @param message 当前问题，不能为空且长度受限
     * @param history 已完成的问答，最多四轮，允许省略
     */
    public record ChatRequest(@NotBlank @Size(max = MAX_QUESTION_LENGTH) String message,
                              @Size(max = MAX_HISTORY_TURNS) List<@NotNull @Valid HistoryTurn> history) {
    }

    /**
     * @Author: jtxw
     * @Date: 2026/11/19 17:52:58
     * @Description: 聊天请求的历史轮次，将用户问题和对应回复作为一组传入
     * @param question 此轮用户问题，非空且长度受限
     * @param answer 此轮模型回复，非空且长度受限
     */
    public record HistoryTurn(@NotBlank @Size(max = MAX_QUESTION_LENGTH) String question,
                              @NotBlank @Size(max = MAX_HISTORY_ANSWER_LENGTH) String answer) {
    }
}
