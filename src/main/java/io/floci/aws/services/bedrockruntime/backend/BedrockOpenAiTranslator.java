package io.floci.aws.services.bedrockruntime.backend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsEventStreamWriter;
import io.floci.aws.core.common.AwsException;
import org.jboss.logging.Logger;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Translates between the Bedrock Runtime Converse wire shape and the OpenAI
 * Chat Completions wire shape used by Ollama, OpenRouter, LiteLLM, vLLM, etc.
 */
final class BedrockOpenAiTranslator {

    private static final Logger LOG = Logger.getLogger(BedrockOpenAiTranslator.class);

    private BedrockOpenAiTranslator() {
    }

    static ObjectNode toOpenAiRequest(ObjectMapper mapper, ObjectNode bedrockRequest, String resolvedModel, boolean stream) {
        ObjectNode openAi = mapper.createObjectNode();
        openAi.put("model", resolvedModel);
        openAi.put("stream", stream);
        if (stream) {
            // Not all OpenAI-compatible backends honor this, but the ones that do (including
            // OpenAI itself) only include token usage in the final chunk when asked for it.
            openAi.putObject("stream_options").put("include_usage", true);
        }
        ArrayNode openAiMessages = openAi.putArray("messages");

        JsonNode system = bedrockRequest.path("system");
        if (system.isArray()) {
            for (JsonNode block : system) {
                String text = block.path("text").asText(null);
                if (text != null) {
                    ObjectNode msg = openAiMessages.addObject();
                    msg.put("role", "system");
                    msg.put("content", text);
                } else if (!block.has("cachePoint")) {
                    throw new AwsException("ValidationException",
                            "The proxy supports only text and cachePoint blocks in system.", 400);
                }
            }
        }

        JsonNode messages = bedrockRequest.path("messages");
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                translateMessage(mapper, openAiMessages, message);
            }
        }

        JsonNode toolConfig = bedrockRequest.path("toolConfig");
        JsonNode tools = toolConfig.path("tools");
        if (tools.isArray() && !tools.isEmpty()) {
            ArrayNode openAiTools = openAi.putArray("tools");
            for (JsonNode tool : tools) {
                JsonNode toolSpec = tool.path("toolSpec");
                if (!toolSpec.isObject()) {
                    continue;
                }
                ObjectNode function = putFunctionRef(openAiTools.addObject(), toolSpec.path("name").asText(""));
                if (toolSpec.hasNonNull("description")) {
                    function.put("description", toolSpec.path("description").asText());
                }
                JsonNode schema = toolSpec.path("inputSchema").path("json");
                if (schema.isObject()) {
                    function.set("parameters", schema.deepCopy());
                }
            }

            // "auto" needs no explicit output: it's OpenAI's default whenever tools are present.
            JsonNode toolChoice = toolConfig.path("toolChoice");
            if (toolChoice.hasNonNull("any")) {
                openAi.put("tool_choice", "required");
            } else if (toolChoice.hasNonNull("tool")) {
                String toolName = toolChoice.path("tool").path("name").asText("");
                if (!toolName.isBlank()) {
                    putFunctionRef(openAi.putObject("tool_choice"), toolName);
                }
            }
        }

        JsonNode inferenceConfig = bedrockRequest.path("inferenceConfig");
        JsonNode maxTokens = inferenceConfig.path("maxTokens");
        if (maxTokens.isNumber()) {
            openAi.put("max_tokens", maxTokens.asInt());
        }
        JsonNode temperature = inferenceConfig.path("temperature");
        if (temperature.isNumber()) {
            openAi.put("temperature", temperature.asDouble());
        }
        JsonNode topP = inferenceConfig.path("topP");
        if (topP.isNumber()) {
            openAi.put("top_p", topP.asDouble());
        }
        JsonNode stopSequences = inferenceConfig.path("stopSequences");
        if (stopSequences.isArray() && !stopSequences.isEmpty()) {
            openAi.set("stop", stopSequences.deepCopy());
        }

        translateOutputConfig(mapper, bedrockRequest.path("outputConfig"), openAi);
        applyAdditionalModelRequestFields(bedrockRequest.path("additionalModelRequestFields"), openAi);
        noteIgnoredFields(bedrockRequest);

        return openAi;
    }

    /** Fields the translator owns; additionalModelRequestFields must not be able to break the request. */
    private static final Set<String> PROTECTED_REQUEST_FIELDS =
            Set.of("model", "messages", "stream", "stream_options", "tools", "tool_choice");

    /**
     * Forwards the top-level members of {@code additionalModelRequestFields} to the backend as
     * extra request-body fields (for example {@code reasoning_effort} or provider specific
     * parameters that LiteLLM and similar gateways accept). Members that would replace a field the
     * translator owns are ignored with a warning.
     */
    private static void applyAdditionalModelRequestFields(JsonNode additional, ObjectNode openAi) {
        if (additional.isMissingNode() || additional.isNull()) {
            return;
        }
        if (!additional.isObject()) {
            throw new AwsException("ValidationException", "additionalModelRequestFields must be an object.", 400);
        }
        Iterator<Map.Entry<String, JsonNode>> fields = additional.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (PROTECTED_REQUEST_FIELDS.contains(field.getKey())) {
                LOG.warnv("Ignoring additionalModelRequestFields member that the proxy owns: {0}", field.getKey());
                continue;
            }
            openAi.set(field.getKey(), field.getValue().deepCopy());
        }
    }

    /** Converse request members with no OpenAI equivalent are accepted but ignored, and say so in the log. */
    private static void noteIgnoredFields(JsonNode request) {
        for (String name : List.of("guardrailConfig", "performanceConfig", "requestMetadata", "promptVariables")) {
            if (request.has(name)) {
                LOG.warnv("Converse field {0} is accepted but not applied by the bedrock-runtime proxy backend", name);
            }
        }
    }

    /**
     * Converts Bedrock output controls to the OpenAI wire shape, rejecting malformed
     * configuration before either Converse operation contacts the backend.
     */
    private static void translateOutputConfig(ObjectMapper mapper, JsonNode outputConfig, ObjectNode openAi) {
        if (outputConfig.isMissingNode()) {
            return;
        }
        if (!outputConfig.isObject()) {
            throw new AwsException("ValidationException", "outputConfig must be an object.", 400);
        }
        // Effort is independent of textFormat, so effort-only requests must survive the return below.
        if (outputConfig.has("effort")) {
            JsonNode effort = outputConfig.path("effort");
            if (!effort.isTextual() || !List.of("low", "medium", "high", "xhigh", "max").contains(effort.asText())) {
                throw new AwsException("ValidationException",
                        "outputConfig.effort must be low, medium, high, xhigh, or max.", 400);
            }
            openAi.put("reasoning_effort", effort.asText());
        }
        JsonNode textFormat = outputConfig.path("textFormat");
        if (textFormat.isMissingNode()) {
            return;
        }
        JsonNode structure = textFormat.path("structure");
        JsonNode definition = structure.path("jsonSchema");
        JsonNode schemaString = definition.path("schema");
        // structure is an AWS union: jsonSchema must be its only member.
        if (!"json_schema".equals(textFormat.path("type").asText())
                || !structure.isObject() || structure.size() != 1 || !definition.isObject()
                || !schemaString.isTextual()
                || (definition.has("name") && !definition.path("name").isTextual())
                || (definition.has("description") && !definition.path("description").isTextual())) {
            throw new AwsException("ValidationException",
                    "outputConfig.textFormat requires type json_schema and structure.jsonSchema with a schema string"
                            + " and optional string name/description.", 400);
        }
        JsonNode schema;
        try {
            // Decimal constraints must retain their precision, and trailing JSON values are malformed input.
            schema = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readTree(schemaString.asText());
        } catch (JsonProcessingException e) {
            throw new AwsException("ValidationException", "Structured output schema must be a JSON object.", 400);
        }
        if (schema == null || !schema.isObject()) {
            throw new AwsException("ValidationException", "Structured output schema must be a JSON object.", 400);
        }
        // OpenAI requires a name that Bedrock makes optional, so use a stable default.
        // Leave strict unset: its extra restrictions would reject schemas with optional properties.
        ObjectNode responseFormat = openAi.putObject("response_format").put("type", "json_schema");
        ObjectNode jsonSchema = responseFormat.putObject("json_schema");
        jsonSchema.put("name", definition.path("name").asText("response"));
        jsonSchema.set("schema", schema);
        if (definition.has("description")) {
            jsonSchema.set("description", definition.path("description").deepCopy());
        }
    }

    /** Builds the OpenAI {@code {type: "function", function: {name}}} shape shared by tools[] entries and tool_choice. */
    private static ObjectNode putFunctionRef(ObjectNode target, String name) {
        target.put("type", "function");
        return target.putObject("function").put("name", name);
    }

    /**
     * Text-only messages retain string content; messages with images use ordered
     * content parts. Tool uses stay on the message, while tool results become
     * standalone OpenAI messages with role=tool.
     */
    private static void translateMessage(ObjectMapper mapper, ArrayNode openAiMessages, JsonNode message) {
        String role = message.path("role").asText("user");
        JsonNode content = message.path("content");
        StringBuilder text = new StringBuilder();
        ArrayNode parts = mapper.createArrayNode();
        boolean hasParts = false;
        ArrayNode toolCalls = null;

        if (content.isArray()) {
            for (JsonNode block : content) {
                if (block.has("toolResult")) {
                    JsonNode toolResult = block.path("toolResult");
                    ObjectNode toolMsg = openAiMessages.addObject();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", toolResult.path("toolUseId").asText(""));
                    toolMsg.put("content", extractToolResultText(toolResult.path("content")));
                    continue;
                }
                if (block.has("toolUse")) {
                    JsonNode toolUse = block.path("toolUse");
                    if (toolCalls == null) {
                        toolCalls = mapper.createArrayNode();
                    }
                    ObjectNode toolCall = toolCalls.addObject();
                    toolCall.put("id", toolUse.path("toolUseId").asText(""));
                    toolCall.put("type", "function");
                    ObjectNode function = toolCall.putObject("function");
                    function.put("name", toolUse.path("name").asText(""));
                    function.put("arguments", toJsonString(mapper, toolUse.path("input")));
                    continue;
                }
                if (block.has("image")) {
                    parts.addObject().put("type", "image_url").putObject("image_url")
                            .put("url", imageDataUrl(block.path("image")));
                    hasParts = true;
                    continue;
                }
                if (block.has("document")) {
                    hasParts |= translateDocument(block.path("document"), parts, text);
                    continue;
                }
                if (block.has("cachePoint")) {
                    continue;
                }
                if (block.has("reasoningContent")) {
                    // Prior reasoning is not replayed to OpenAI-compatible backends.
                    LOG.debug("Dropping reasoningContent block from conversation history");
                    continue;
                }
                String blockText = block.path("text").asText(null);
                if (blockText != null) {
                    parts.addObject().put("type", "text").put("text", blockText);
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(blockText);
                    continue;
                }
                throw new AwsException("ValidationException",
                        "The proxy does not support content block(s) " + fieldNames(block)
                                + "; supported blocks are text, image, document, toolUse, toolResult, cachePoint.",
                        400);
            }
        }

        if (text.length() > 0 || hasParts || toolCalls != null) {
            ObjectNode msg = openAiMessages.addObject();
            msg.put("role", role);
            if (hasParts) {
                msg.set("content", parts);
            } else if (text.length() > 0) {
                msg.put("content", text.toString());
            } else {
                // OpenAI's spec treats content as optional when tool_calls is present; some
                // strict backends reject an empty string here instead of null.
                msg.putNull("content");
            }
            if (toolCalls != null) {
                msg.set("tool_calls", toolCalls);
            }
        }
    }

    private static final Set<String> TEXT_DOCUMENT_FORMATS = Set.of("txt", "md", "csv", "html");
    private static final Set<String> UNSUPPORTED_DOCUMENT_FORMATS = Set.of("doc", "docx", "xls", "xlsx");
    private static final Pattern HTML_SCRIPT_STYLE = Pattern.compile("(?is)<(script|style)\\b.*?</\\1\\s*>");
    private static final Pattern HTML_BREAKS = Pattern.compile("(?i)<(br|/p|/div|/li|/tr|/h[1-6])\\s*/?>");
    private static final Pattern HTML_TAG = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern BLANK_RUNS = Pattern.compile("[ \\t]*\\n(?:[ \\t]*\\n)+");

    /**
     * Converts a Converse {@code document} block. Text formats (txt, md, csv, html) are decoded and
     * sent as a text part wrapped in a {@code <document>} element (HTML is reduced to its text); pdf
     * is sent as an OpenAI {@code file} content part (best effort: the backend must support it, and
     * its rejection comes back as ModelErrorException); doc, docx, xls and xlsx are rejected with a
     * ValidationException, because there is no extraction for them. Nothing is dropped silently.
     *
     * @return true when a non-text part was added, so the message must use content parts
     */
    private static boolean translateDocument(JsonNode document, ArrayNode parts, StringBuilder text) {
        String format = document.path("format").asText("");
        String name = document.path("name").asText("document");
        if (UNSUPPORTED_DOCUMENT_FORMATS.contains(format)) {
            throw new AwsException("ValidationException", "Document format " + format
                    + " is not supported by the bedrock-runtime proxy backend (no text extraction); "
                    + "supported formats are pdf, txt, md, csv and html.", 400);
        }
        if (!TEXT_DOCUMENT_FORMATS.contains(format) && !"pdf".equals(format)) {
            throw new AwsException("ValidationException",
                    "Document format must be pdf, csv, doc, docx, xls, xlsx, html, txt, or md.", 400);
        }
        JsonNode source = document.path("source");
        JsonNode bytes = source.path("bytes");
        if (!source.isObject() || source.size() != 1 || !bytes.isTextual() || bytes.asText().isEmpty()) {
            throw new AwsException("ValidationException",
                    "The proxy supports only inline document source.bytes; provide non-empty Base64 bytes, not S3 sources.",
                    400);
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(bytes.asText());
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException", "Document source.bytes must contain valid Base64.", 400);
        }
        if ("pdf".equals(format)) {
            ObjectNode file = parts.addObject().put("type", "file").putObject("file");
            file.put("filename", name + ".pdf");
            file.put("file_data", "data:application/pdf;base64," + bytes.asText());
            return true;
        }
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded)).toString();
        } catch (CharacterCodingException e) {
            throw new AwsException("ValidationException", "Document " + format + " bytes must be valid UTF-8 text.", 400);
        }
        if ("html".equals(format)) {
            content = htmlToText(content);
        }
        String wrapped = "<document name=\"" + name.replace("\"", "&quot;") + "\" format=\"" + format + "\">\n"
                + content + "\n</document>";
        parts.addObject().put("type", "text").put("text", wrapped);
        if (text.length() > 0) {
            text.append('\n');
        }
        text.append(wrapped);
        return false;
    }

    static String htmlToText(String html) {
        String stripped = HTML_SCRIPT_STYLE.matcher(html).replaceAll("");
        stripped = HTML_BREAKS.matcher(stripped).replaceAll("\n");
        stripped = HTML_TAG.matcher(stripped).replaceAll("");
        stripped = stripped.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        return BLANK_RUNS.matcher(stripped).replaceAll("\n\n").strip();
    }

    private static String fieldNames(JsonNode block) {
        List<String> names = new ArrayList<>();
        block.fieldNames().forEachRemaining(names::add);
        return names.toString();
    }

    private static String imageDataUrl(JsonNode image) {
        String format = image.path("format").asText("");
        if (!List.of("png", "jpeg", "gif", "webp").contains(format)) {
            throw new AwsException("ValidationException", "Image format must be png, jpeg, gif, or webp.", 400);
        }
        JsonNode source = image.path("source");
        JsonNode bytes = source.path("bytes");
        if (!source.isObject() || source.size() != 1 || !bytes.isTextual() || bytes.asText().isEmpty()) {
            throw new AwsException("ValidationException",
                    "The proxy supports only inline image source.bytes; provide non-empty Base64 bytes, not S3 sources.",
                    400);
        }
        String encoded = bytes.asText();
        try {
            // Validate aligned quartets in bounded chunks instead of allocating the whole decoded image.
            for (int offset = 0; offset < encoded.length();) {
                int end = offset + Math.min(8192, encoded.length() - offset);
                int decodedLength = Base64.getDecoder().decode(encoded.substring(offset, end)).length;
                if (end < encoded.length() && decodedLength != (end - offset) / 4 * 3) {
                    throw new IllegalArgumentException("Image padding must occur only at the end.");
                }
                offset = end;
            }
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException", "Image source.bytes must contain valid Base64.", 400);
        }
        return "data:image/" + format + ";base64," + encoded;
    }

    private static String extractToolResultText(JsonNode toolResultContent) {
        if (!toolResultContent.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : toolResultContent) {
            if (!part.isObject() || part.size() != 1 || (!part.has("text") && !part.has("json"))) {
                throw new AwsException("ValidationException",
                        "The proxy supports only text or json blocks in toolResult.content.",
                        400);
            }
            String text = part.path("text").asText(null);
            if (text == null && part.has("json")) {
                text = part.path("json").toString();
            }
            if (text != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(text);
            }
        }
        return sb.toString();
    }

    private static String toJsonString(ObjectMapper mapper, JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            LOG.warnv("Failed to serialize toolUse input as JSON: {0}", e.getMessage());
            return "{}";
        }
    }

    static ObjectNode toBedrockResponse(ObjectMapper mapper, JsonNode openAiResponse, long latencyMs) {
        JsonNode choice = openAiResponse.path("choices").path(0);
        if (!choice.isObject()) {
            // A 2xx response with no choices at all (e.g. an error object the backend still
            // returned with a success status) is not a completion - don't fabricate an empty
            // assistant message out of it.
            throw new AwsException("ModelErrorException",
                    "Proxy backend's response had no choices: " + truncate(openAiResponse.toString(), 512), 424);
        }
        JsonNode message = choice.path("message");
        String finishReason = choice.path("finish_reason").asText("stop");
        JsonNode toolCallsNode = message.path("tool_calls");
        boolean hasToolCalls = toolCallsNode.isArray() && !toolCallsNode.isEmpty();

        ObjectNode root = mapper.createObjectNode();
        ObjectNode output = root.putObject("output");
        ObjectNode outMessage = output.putObject("message");
        outMessage.put("role", "assistant");
        ArrayNode content = outMessage.putArray("content");

        if (hasToolCalls) {
            // Some OpenAI-compatible backends emit assistant text alongside tool_calls (e.g. a
            // preamble like "Let me check that for you") — preserve it as a leading text block
            // instead of dropping it.
            String leadingText = extractMessageText(message);
            if (!leadingText.isBlank()) {
                content.addObject().put("text", leadingText);
            }
            for (JsonNode toolCall : toolCallsNode) {
                ObjectNode toolUse = content.addObject().putObject("toolUse");
                toolUse.put("toolUseId", toolCall.path("id").asText(""));
                JsonNode function = toolCall.path("function");
                toolUse.put("name", function.path("name").asText(""));
                toolUse.set("input", parseToolArguments(mapper, function.path("arguments").asText("{}")));
            }
        } else {
            content.addObject().put("text", extractMessageText(message));
        }

        // stopReason is derived from whether we actually emitted toolUse content, not from
        // finish_reason alone: a backend can report finish_reason=tool_calls without a usable
        // tool_calls array, which must not produce a Converse response claiming tool_use with
        // no toolUse blocks in it.
        root.put("stopReason", hasToolCalls ? "tool_use" : mapFinishReason(finishReason));

        JsonNode usage = openAiResponse.path("usage");
        ObjectNode usageOut = root.putObject("usage");
        usageOut.put("inputTokens", usage.path("prompt_tokens").asInt(0));
        usageOut.put("outputTokens", usage.path("completion_tokens").asInt(0));
        usageOut.put("totalTokens", usage.path("total_tokens").asInt(0));

        root.putObject("metrics").put("latencyMs", latencyMs);

        return root;
    }

    /**
     * Translates an OpenAI Chat Completions SSE stream ({@code data: {...}} lines, terminated by
     * {@code data: [DONE]}) into Bedrock ConverseStream events, writing each frame to {@code out}
     * as soon as it's known instead of buffering the whole response - text deltas reach the
     * client as they arrive from the upstream.
     *
     * <p>By the time this runs, JAX-RS has already committed the 200 response (messageStart is
     * about to be written), so a failure discovered here - a truncated stream that never reaches
     * a finish_reason or "[DONE]" - can no longer become an HTTP error status. It's written as an
     * in-band {@code modelStreamErrorException} event instead, matching how real Bedrock reports
     * a mid-stream failure after the response has already started.
     *
     * <p>Tool calls are accumulated across chunks and emitted as a single complete
     * contentBlockDelta once the stream ends, rather than as incremental argument fragments -
     * simpler than real Bedrock's fragment-by-fragment streaming, but SDKs consume both the same
     * way since they just concatenate delta.toolUse.input across events for a given
     * contentBlockIndex before parsing it as JSON.
     */
    static void streamBedrockEvents(ObjectMapper mapper, Stream<String> sseLines, OutputStream out, long startNanos) {
        AwsEventStreamWriter.writeEvent(mapper, out, "messageStart", mapper.createObjectNode().put("role", "assistant"));

        boolean anyContentDeltaWritten = false;
        Map<Integer, ToolCallAccumulator> toolCalls = new LinkedHashMap<>();
        String finishReason = "stop";
        boolean sawFinishReason = false;
        boolean sawDone = false;
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;

        try (sseLines) {
            Iterator<String> lineIterator = sseLines.iterator();
            String rawLine;
            while ((rawLine = readLine(lineIterator)) != null) {
                String line = rawLine.strip();
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).strip();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    sawDone = true;
                    continue;
                }
                JsonNode chunk;
                try {
                    chunk = mapper.readTree(data);
                } catch (Exception e) {
                    LOG.warnv("Skipping malformed SSE chunk from proxy backend: {0}", e.getMessage());
                    continue;
                }

                JsonNode usage = chunk.path("usage");
                if (usage.isObject()) {
                    promptTokens = usage.path("prompt_tokens").asInt(promptTokens);
                    completionTokens = usage.path("completion_tokens").asInt(completionTokens);
                    totalTokens = usage.path("total_tokens").asInt(totalTokens);
                }

                JsonNode choice = chunk.path("choices").path(0);
                String reason = choice.path("finish_reason").asText(null);
                if (reason != null) {
                    finishReason = reason;
                    sawFinishReason = true;
                }

                JsonNode delta = choice.path("delta");
                if (writeContentDelta(mapper, out, delta)) {
                    anyContentDeltaWritten = true;
                }
                writeToolCalls(delta, toolCalls);
            }
        }

        boolean streamCompleted = sawFinishReason || sawDone;
        if (!streamCompleted) {
            writeTruncatedException(mapper, out);
            return;
        }

        if (anyContentDeltaWritten) {
            AwsEventStreamWriter.writeEvent(mapper, out, "contentBlockStop",
                    mapper.createObjectNode().put("contentBlockIndex", 0));
        }

        List<ToolCallAccumulator> validToolCalls = collectValidToolCalls(mapper, toolCalls);
        int blockIndex = anyContentDeltaWritten ? 1 : 0;
        for (ToolCallAccumulator acc : validToolCalls) {
            writeToolCalls(mapper, out, acc, blockIndex);
            blockIndex++;
        }

        boolean hasToolCalls = !validToolCalls.isEmpty();
        AwsEventStreamWriter.writeEvent(mapper, out, "messageStop",
                mapper.createObjectNode().put("stopReason", hasToolCalls ? "tool_use" : mapFinishReason(finishReason)));

        writeMetadata(mapper, out, startNanos, promptTokens, completionTokens, totalTokens);
    }

    private static String readLine(Iterator<String> lineIterator) {
        try {
            if (!lineIterator.hasNext()) {
                return null;
            }
            return lineIterator.next();
        } catch (RuntimeException e) {
            // The upstream connection failed mid-read (e.g. it dropped the connection).
            // Treat exactly like reaching EOF without a finish_reason/[DONE]: fall through
            // to the truncation handling instead of letting an UncheckedIOException
            // propagate and abruptly kill the response mid-stream.
            LOG.warnv(e, "Bedrock proxy backend connection failed while streaming");
            return null;
        }
    }

    private static void writeMetadata(ObjectMapper mapper, OutputStream out, long startNanos, int promptTokens, int completionTokens, int totalTokens) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        ObjectNode metadata = mapper.createObjectNode();
        metadata.putObject("usage")
                .put("inputTokens", promptTokens)
                .put("outputTokens", completionTokens)
                .put("totalTokens", totalTokens);
        metadata.putObject("metrics").put("latencyMs", latencyMs);
        AwsEventStreamWriter.writeEvent(mapper, out, "metadata", metadata);
    }

    private static void writeToolCalls(ObjectMapper mapper, OutputStream out, ToolCallAccumulator acc, int blockIndex) {
        ObjectNode startEvent = mapper.createObjectNode();
        startEvent.put("contentBlockIndex", blockIndex);
        ObjectNode toolUseStart = startEvent.putObject("start").putObject("toolUse");
        toolUseStart.put("toolUseId", acc.id);
        toolUseStart.put("name", acc.name);
        AwsEventStreamWriter.writeEvent(mapper, out, "contentBlockStart", startEvent);

        ObjectNode deltaEvent = mapper.createObjectNode();
        deltaEvent.put("contentBlockIndex", blockIndex);
        deltaEvent.putObject("delta").putObject("toolUse").put("input", acc.arguments.toString());
        AwsEventStreamWriter.writeEvent(mapper, out, "contentBlockDelta", deltaEvent);

        AwsEventStreamWriter.writeEvent(mapper, out, "contentBlockStop",
                mapper.createObjectNode().put("contentBlockIndex", blockIndex));
    }

    private static List<ToolCallAccumulator> collectValidToolCalls(ObjectMapper mapper, Map<Integer, ToolCallAccumulator> toolCalls) {
        // A tool_calls delta only becomes a usable toolUse block once it has a non-blank id,
        // a non-blank name, and arguments that actually form a JSON object - a partial or
        // truncated fragment (e.g. an id with no name, or unclosed argument JSON) is dropped
        // instead of being surfaced as a broken tool_use block with empty identity fields.
        List<ToolCallAccumulator> validToolCalls = new ArrayList<>();
        for (ToolCallAccumulator acc : toolCalls.values()) {
            if (isUsableToolCall(mapper, acc)) {
                validToolCalls.add(acc);
            } else {
                LOG.warnv("Dropping incomplete or invalid tool_calls fragment from proxy backend: id={0}, name={1}",
                        acc.id, acc.name);
            }
        }
        return validToolCalls;
    }

    private static void writeTruncatedException(ObjectMapper mapper, OutputStream out) {
        // The stream ended (upstream closed the connection, or we ran out of lines) without
        // ever reaching a finish_reason or "[DONE]" - a truncated generation. The HTTP status
        // is already 200, so this can only be reported in-band.
        ObjectNode errorPayload = mapper.createObjectNode();
        errorPayload.put("message", "Proxy backend's ConverseStream response ended without a finish_reason "
                + "or \"[DONE]\" terminator - the stream may have been truncated.");
        AwsEventStreamWriter.writeException(mapper, out, "modelStreamErrorException", errorPayload);
    }

    private static void writeToolCalls(JsonNode delta, Map<Integer, ToolCallAccumulator> toolCalls) {
        JsonNode toolCallDeltas = delta.path("tool_calls");
        if (!toolCallDeltas.isArray()) {
            return;
        }
        for (JsonNode toolCallDelta : toolCallDeltas) {
            int index = toolCallDelta.path("index").asInt(0);
            ToolCallAccumulator acc = toolCalls.computeIfAbsent(index, i -> new ToolCallAccumulator());
            String id = toolCallDelta.path("id").asText(null);
            if (id != null) {
                acc.id = id;
            }
            JsonNode function = toolCallDelta.path("function");
            String name = function.path("name").asText(null);
            if (name != null) {
                acc.name = name;
            }
            String argsFragment = function.path("arguments").asText(null);
            if (argsFragment != null) {
                acc.arguments.append(argsFragment);
            }
        }
    }

    private static boolean writeContentDelta(ObjectMapper mapper, OutputStream out, JsonNode delta) {
        String contentDelta = delta.path("content").asText(null);
        if (contentDelta == null || contentDelta.isEmpty()) {
            return false;
        }

        ObjectNode deltaEvent = mapper.createObjectNode();
        deltaEvent.put("contentBlockIndex", 0);
        deltaEvent.putObject("delta").put("text", contentDelta);
        AwsEventStreamWriter.writeEvent(mapper, out, "contentBlockDelta", deltaEvent);
        return true;
    }

    private static boolean isUsableToolCall(ObjectMapper mapper, ToolCallAccumulator acc) {
        if (acc.id == null || acc.id.isBlank() || acc.name == null || acc.name.isBlank()) {
            return false;
        }
        try {
            return mapper.readTree(acc.arguments.toString()).isObject();
        } catch (Exception e) {
            return false;
        }
    }

    private static final class ToolCallAccumulator {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }

    private static String extractMessageText(JsonNode message) {
        JsonNode content = message.path("content");
        if (content.isTextual()) {
            return content.asText("");
        }
        if (!content.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : content) {
            String text = part.path("text").asText(null);
            if (text == null) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(text);
        }
        return sb.toString();
    }

    private static ObjectNode parseToolArguments(ObjectMapper mapper, String argumentsJson) {
        try {
            JsonNode parsed = mapper.readTree(argumentsJson);
            if (parsed.isObject()) {
                return (ObjectNode) parsed;
            }
        } catch (Exception e) {
            LOG.warnv("Failed to parse tool_call arguments as JSON: {0}", e.getMessage());
        }
        return mapper.createObjectNode();
    }

    private static String mapFinishReason(String finishReason) {
        return "length".equals(finishReason) ? "max_tokens" : "end_turn";
    }

    private static String truncate(String value, int maxLength) {
        return value != null && value.length() > maxLength ? value.substring(0, maxLength) + "…" : value;
    }
}
