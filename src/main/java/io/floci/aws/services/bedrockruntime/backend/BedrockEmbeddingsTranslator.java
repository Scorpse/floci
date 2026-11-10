package io.floci.aws.services.bedrockruntime.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;

import java.util.Locale;

/**
 * Translates the InvokeModel bodies of the Bedrock embedding models (Amazon Titan Text Embeddings
 * and Cohere Embed) to and from the OpenAI {@code /embeddings} wire shape. Only embedding models
 * are translated: the other InvokeModel bodies are model specific and stay unsupported in proxy mode.
 */
final class BedrockEmbeddingsTranslator {

    enum Family { TITAN, COHERE }

    /** A parsed InvokeModel embedding request: the OpenAI request body and what the response needs. */
    record Request(Family family, ObjectNode openAiRequest, int inputCount, Integer dimensions,
                   boolean cohereByType) {
    }

    private BedrockEmbeddingsTranslator() {
    }

    /** The embedding family of a Bedrock model id (inference profile prefixes included), or null. */
    static Family familyOf(String modelId) {
        String id = modelId == null ? "" : modelId.toLowerCase(Locale.ROOT);
        if (id.contains("amazon.titan-embed-")) {
            return Family.TITAN;
        }
        if (id.contains("cohere.embed-")) {
            return Family.COHERE;
        }
        return null;
    }

    static Request toOpenAiRequest(ObjectMapper mapper, Family family, byte[] body, String resolvedModel) {
        JsonNode in;
        try {
            in = mapper.readTree(body == null ? new byte[0] : body);
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Malformed request body: " + e.getMessage(), 400);
        }
        if (in == null || !in.isObject()) {
            throw new AwsException("ValidationException", "The InvokeModel body must be a JSON object.", 400);
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("model", resolvedModel);
        out.put("encoding_format", "float");
        ArrayNode input = out.putArray("input");
        Integer dimensions = null;
        boolean byType = false;
        if (family == Family.TITAN) {
            JsonNode text = in.path("inputText");
            if (!text.isTextual() || text.asText().isEmpty()) {
                throw new AwsException("ValidationException", "inputText is required and must be a non-empty string.", 400);
            }
            input.add(text.asText());
            JsonNode dims = in.path("dimensions");
            if (dims.isInt() && dims.asInt() > 0) {
                dimensions = dims.asInt();
                out.put("dimensions", dimensions);
            }
        } else {
            JsonNode texts = in.path("texts");
            if (!texts.isArray() || texts.isEmpty()) {
                throw new AwsException("ValidationException", "texts is required and must be a non-empty array of strings.", 400);
            }
            for (JsonNode text : texts) {
                if (!text.isTextual()) {
                    throw new AwsException("ValidationException", "texts must contain only strings.", 400);
                }
                input.add(text.asText());
            }
            // Cohere answers {"embeddings": {"float": [...]}} (embeddings_by_type) once embedding_types is sent.
            byType = in.path("embedding_types").isArray() && !in.path("embedding_types").isEmpty();
            if (byType) {
                for (JsonNode type : in.path("embedding_types")) {
                    if (!"float".equals(type.asText())) {
                        throw new AwsException("ValidationException",
                                "The proxy supports only the float embedding type.", 400);
                    }
                }
            }
        }
        return new Request(family, out, input.size(), dimensions, byType);
    }

    static byte[] toBedrockResponse(ObjectMapper mapper, Request request, JsonNode openAiResponse) {
        JsonNode data = openAiResponse.path("data");
        if (!data.isArray() || data.size() != request.inputCount()) {
            throw new AwsException("ModelErrorException", "Proxy backend's embeddings response had "
                    + (data.isArray() ? data.size() : 0) + " embeddings for " + request.inputCount() + " inputs.", 424);
        }
        ArrayNode vectors = mapper.createArrayNode();
        for (JsonNode item : data) {
            JsonNode embedding = item.path("embedding");
            if (!embedding.isArray() || embedding.isEmpty()) {
                throw new AwsException("ModelErrorException",
                        "Proxy backend's embeddings response did not contain float vectors.", 424);
            }
            if (request.dimensions() != null && embedding.size() != request.dimensions()) {
                throw new AwsException("ModelErrorException", "Proxy backend returned " + embedding.size()
                        + " dimensions but " + request.dimensions() + " were requested.", 424);
            }
            vectors.add(embedding);
        }
        int tokens = openAiResponse.path("usage").path("prompt_tokens").asInt(0);
        ObjectNode root = mapper.createObjectNode();
        if (request.family() == Family.TITAN) {
            root.set("embedding", vectors.get(0));
            root.put("inputTextTokenCount", tokens);
            root.putObject("embeddingsByType").set("float", vectors.get(0));
        } else {
            root.put("id", "floci-embed-" + java.util.UUID.randomUUID());
            if (request.cohereByType()) {
                root.putObject("embeddings").set("float", vectors);
                root.put("response_type", "embeddings_by_type");
            } else {
                root.set("embeddings", vectors);
                root.put("response_type", "embeddings_floats");
            }
        }
        try {
            return mapper.writeValueAsBytes(root);
        } catch (Exception e) {
            throw new AwsException("InternalServerException", "Failed to serialize InvokeModel response.", 500);
        }
    }
}
