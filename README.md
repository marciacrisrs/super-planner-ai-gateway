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

Primeiro esqueleto do serviço, preparado para execução no Google Cloud Run:

- Kotlin + Ktor
- Java 21
- endpoint `GET /health`
- endpoint `GET /ready`
- container Docker
- porta configurável por `PORT`

## Próximos passos

1. Contratos de API entre Android e Gateway.
2. Autenticação e autorização.
3. Integração com Gemini via Vertex AI.
4. Caso de uso `Organize sua semana`.
5. Validação das propostas pelo domínio/Planning Engine.
6. Observabilidade, métricas e controle de custos.
7. Pipeline de CI/CD e deploy no Cloud Run.

## Segurança

Segredos e credenciais não devem ser armazenados no aplicativo Android nem no código-fonte. A configuração de produção deverá usar serviços de gerenciamento de segredos do Google Cloud.
