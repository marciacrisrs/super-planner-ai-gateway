# Super Planner AI Gateway

Backend de orquestração de IA do Super Planner, conectando o aplicativo a modelos de IA e mantendo as regras de negócio no motor de planejamento.

## Arquitetura

```text
Super Planner Android
        |
        | HTTPS
        v
Super Planner AI Gateway (Cloud Run)
        |
        +--> Gemini / Vertex AI
        |
        +--> autenticação e autorização
        +--> validação de entrada e saída
        +--> observabilidade
        +--> controle de uso
        |
        v
Planning Engine / domínio do Super Planner
```

### Princípio

> **IA conversa, domínio decide.**

O gateway interpreta intenções, orquestra chamadas de IA e transforma respostas em propostas estruturadas. Regras determinísticas de planejamento continuam pertencendo ao domínio do Super Planner.

## Estado atual

O gateway está preparado para execução no Google Cloud Run e já possui integração inicial com o Gemini via Google Gen AI SDK:

- Kotlin + Ktor
- Java 21
- Google Gen AI SDK
- Gemini via Gemini Enterprise Agent Platform / Vertex AI
- endpoint `GET /health`
- endpoint `GET /ready`
- endpoint `POST /v1/ai/generate`
- container Docker
- porta configurável por `PORT`

## Configuração local

Defina:

```bash
export GOOGLE_CLOUD_PROJECT="seu-project-id"
export GOOGLE_CLOUD_LOCATION="us-central1"
export GEMINI_MODEL="gemini-2.5-flash"
```

Em ambiente Google Cloud, o SDK utiliza as credenciais padrão do ambiente (Application Default Credentials). Não coloque chaves privadas no repositório.

## Exemplo de chamada

```bash
curl -X POST http://localhost:8080/v1/ai/generate \
  -H "Content-Type: application/json" \
  -d '{"prompt":"Explique o princípio IA conversa, domínio decide."}'
```

Resposta esperada:

```json
{
  "text": "...",
  "model": "gemini-2.5-flash"
}
```

## Próximos passos

1. Contratos de API entre Android e Gateway.
2. Autenticação e autorização.
3. Respostas estruturadas para o domínio.
4. Caso de uso `Organize sua semana`.
5. Validação das propostas pelo domínio/Planning Engine.
6. Observabilidade, métricas e controle de custos.
7. Pipeline de CI/CD e deploy no Cloud Run.

## Segurança

Segredos e credenciais não devem ser armazenados no aplicativo Android nem no código-fonte. A configuração de produção deverá usar os mecanismos de identidade e gerenciamento de segredos do Google Cloud.
