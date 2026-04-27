# CLAUDE.md (ccr-junie)

## Project Overview
This is a Java (Spring Boot) implementation of the Claude Code Router core proxy functionality. It is designed to be a lightweight, high-performance alternative to the TypeScript implementation, specifically optimized for streaming (SSE) pass-through.

**Current Status**: Verified Working. 
The module correctly handles both streaming and non-streaming requests from Claude Code and forwards them to Zhipu AI (or any Anthropic-compatible API).
It also supports the `/v1/chat/completions` endpoint and provides automatic protocol transformation between OpenAI and Anthropic formats.

## Working Environment
- **Java Version**: 17
- **Framework**: Spring Boot 3.2.5 (WebFlux)
- **Port**: 3456 (default)
- **Target URL**: Configured in `src/main/resources/application.properties`

## Build and Run Commands

### Build Project
```bash
mvn clean package
```

### Run Project
```bash
mvn spring-boot:run
```

### Run Tests
```bash
mvn test
```

## Core Architecture
- **Routing System (v2)**: The `RouterService` implements a routing logic that mirrors the original TypeScript project:
  - Supports explicit `Provider,Model` input.
  - Automatically detects scenarios: `longContext` (if estimated tokens > `longContextThreshold`), `webSearch` (presence of web_search tool), `think` (presence of `thinking` field), and `background` (model contains `haiku`).
  - Estimates tokens as `characters / 4`.
- **Model Rewriting**: Before forwarding, the server automatically updates the `model` field in the request body to match the target model name specified in the router configuration.
- **Configuration**: Managed in `application.properties` via `CcrConfig`. Use `ProviderName,ModelName` format for router mappings to match the `ccr` project style.

- **Transformer System**: The `TransformerService` provides automatic protocol conversion:
  - **OpenAI -> Anthropic**: Converts `/v1/chat/completions` requests to Anthropic format (extracts system prompt, sets max_tokens).
  - **Anthropic -> OpenAI**: Converts Anthropic responses back to OpenAI format (including SSE streams).

## Critical Guidelines
0. **Referential Implementation**: All logic in `ccr-junie` must strictly reference and mirror the implementation in the original `ccr` (TypeScript) project. This applies to EVERY module (routing, transformation, configuration, etc.). Do not implement features based on assumptions or without checking the equivalent logic in the TS codebase.
1. **Module Integrity**: The core proxying and routing logic is stable. Any modifications to ANY module must be coordinated with the corresponding TypeScript version's logic.
2. **Reactive Implementation**: This project uses Spring WebFlux. Ensure any new features remain non-blocking.
3. **Multi-Provider Support**: When adding new providers, update both `ccr.providers` and `ccr.router` mappings in `application.properties`.
4. **Code Standards**: Code must be as standardized as possible. Use Loggers instead of `System.out`. Avoid hardcoded strings by using objects or constants for reusability.

## Verification Checklist
Before submitting any changes to this module:
- [ ] `mvn test` passes.
- [ ] Manual verification with `curl` for both streaming (`Accept: text/event-stream`) and non-streaming responses.
- [ ] (Optional) Launch Claude Code with `--settings` pointing to this local server to verify end-to-end functionality.

## Roadmap / Missing Features (Compared to TS version)
- **Advanced Transformers**: Support for more non-Anthropic compatible providers (Google, AWS, Groq).
- **Precise Tokenization**: Implement `tiktoken` equivalent (e.g., JTokkit) for accurate `longContext` routing.
- **Plugin System**: Port `token-speed`, Webhook, and Output Manager.
- **Project-Specific Routing**: Automatic detection of project-level config from `.claude/projects/`.
- **Management UI & CLI**: Port the React UI and CLI management commands.
- **System Features**: Auth (`APIKEY`), Caching, and Environment Variable interpolation in config.
