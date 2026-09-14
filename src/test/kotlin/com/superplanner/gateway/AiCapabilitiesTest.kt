package com.superplanner.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json

class AiCapabilitiesTest {
    private class FakeGenerator(private val response: String) : AiTextGenerator {
        override val modelName = "fake-model"
        override fun generate(prompt: String): String = response
    }

    @Test fun capability_response_is_provider_independent() {
        val response = AiCapabilityService(FakeGenerator("{\"explanation\":\"ok\",\"evidenceUsed\":[\"janela de 30 minutos\"],\"confidence\":\"HIGH\"}"))
            .explanation(ExplanationRequest(question="Por quê?", evidence=listOf("janela de 30 minutos")), "req-1")
        assertEquals("1", response.schemaVersion); assertEquals("req-1", response.requestId); assertEquals("fake-model", response.model); assertEquals("ok", response.result["explanation"]?.toString()?.trim('"'))
    }

    @Test fun contextual_explanation_cannot_cite_unsupplied_evidence() {
        assertFailsWith<InvalidAiCapabilityException> { AiCapabilityService(FakeGenerator("""{"explanation":"x","evidenceUsed":["fact-invented"],"confidence":"HIGH"}""")).explanation(ExplanationRequest(question="por quê?", evidence=listOf("fact-real")), "req-evidence") }
    }
    @Test fun contextual_explanation_with_no_evidence_is_low_confidence() {
        val r=AiCapabilityService(FakeGenerator("""{"explanation":"Não há informação suficiente.","evidenceUsed":[],"confidence":"LOW"}""")).explanation(ExplanationRequest(question="por quê?"),"req-insufficient"); assertEquals("LOW",r.result["confidence"]?.toString()?.trim('"'))
    }
    @Test fun contextual_explanation_with_no_evidence_cannot_claim_high_confidence() {
        assertFailsWith<InvalidAiCapabilityException>{AiCapabilityService(FakeGenerator("""{"explanation":"x","evidenceUsed":[],"confidence":"HIGH"}""")).explanation(ExplanationRequest(question="por quê?"),"req-insufficient-high")}
    }
    @Test fun invalid_json_is_rejected(){assertFailsWith<InvalidAiCapabilityException>{AiCapabilityService(FakeGenerator("not-json")).nextAction(NextActionRequest(),"req-2")}}
    @Test fun next_action_must_use_domain_candidate_and_at_most_two_alternatives(){
        assertFailsWith<InvalidAiCapabilityException>{AiCapabilityService(FakeGenerator("""{"recommendedAction":"blocked","reason":"reason","alternatives":["a","b"],"confidence":"HIGH"}""")).nextAction(NextActionRequest(candidates=listOf("a","b")),"req-3")}
        assertFailsWith<InvalidAiCapabilityException>{AiCapabilityService(FakeGenerator("""{"recommendedAction":"a","reason":"reason","alternatives":["b","c","a"],"confidence":"HIGH"}""")).nextAction(NextActionRequest(candidates=listOf("a","b","c")),"req-4")}
        val context=NextActionContext(evidence=listOf("janela disponível"),candidateFacts=listOf(
            NextActionCandidate("a",durationMinutes=30,dependenciesSatisfied=true,conflictFree=true,fitsAvailableTime=true),
            NextActionCandidate("b",durationMinutes=20,dependenciesSatisfied=true,conflictFree=true,fitsAvailableTime=true),
            NextActionCandidate("c",durationMinutes=25,dependenciesSatisfied=true,conflictFree=true,fitsAvailableTime=true),
        ))
        val r=AiCapabilityService(FakeGenerator("""{"recommendedAction":"a","reason":"Cabe na janela disponível.","evidence":["janela disponível"],"alternatives":["b"],"confidence":"HIGH","uncertainty":[],"requiresClarification":false}""")).nextAction(NextActionRequest(candidates=listOf("a","b","c"),context=context),"req-5"); assertEquals("a",r.result["recommendedAction"]?.toString()?.trim('"'))
    }
    @Test fun each_capability_rejects_malformed_contracts(){
        assertFailsWith<InvalidAiCapabilityException>{AiCapabilityService(FakeGenerator("""{"commandType":"CREATE_ACTIVITY_DRAFT","explanation":"x","requiresConfirmation":true,"payload":{},"missingFields":[]}""")).naturalLanguage(NaturalLanguageRequest(message="criar"),"req-nl")}
        assertFailsWith<InvalidAiCapabilityException>{AiCapabilityService(FakeGenerator("""{"explanation":"x","evidenceUsed":[],"confidence":"UNKNOWN"}""")).explanation(ExplanationRequest(question="por quê?"),"req-exp")}
        assertFailsWith<InvalidAiCapabilityException>{AiCapabilityService(FakeGenerator("""{"commandType":"UNKNOWN","requiresConfirmation":true,"payload":{},"explanation":"x"}""")).command(CommandRequest(message="fazer algo"),"req-cmd")}
        assertFailsWith<InvalidAiCapabilityException>{AiCapabilityService(FakeGenerator("""{"insights":[{"title":"x","description":"y","evidence":[],"confidence":"HIGH"}],"recommendations":[1]}""")).insight(InsightRequest(),"req-ins")}
        assertFailsWith<IllegalArgumentException>{AiCapabilityService(FakeGenerator("""{"preferences":[]}""")).preference(PreferenceRequest(schemaVersion="2"),"req-pref")}
        assertFailsWith<InvalidAiCapabilityException>{AiCapabilityService(FakeGenerator("""{"scenario":"x","changes":[],"assumptions":[],"requiresClarification":"false"}""")).scenario(ScenarioRequest(question="e se?"),"req-scenario")}
    }
    @Test fun command_requires_confirmation_for_mutations(){assertFailsWith<InvalidAiCapabilityException>{AiCapabilityService(FakeGenerator("""{"commandType":"CHANGE_ACTIVITY","requiresConfirmation":false,"payload":{"activityId":"a1","changes":{"title":"novo"}},"explanation":"x"}""")).command(CommandRequest(message="mude"),"req-command")}}
    @Test fun planning_command_payloads_are_strict_and_typed(){
        val cases=listOf(
            """{"commandType":"MARK_DELAYED","requiresConfirmation":true,"payload":{"activityId":"a1","delayMinutes":40},"explanation":"atraso"}""" to true,
            """{"commandType":"CANCEL_ACTIVITY","requiresConfirmation":true,"payload":{"activityId":"a1"},"explanation":"cancelar"}""" to true,
            """{"commandType":"CREATE_ACTIVITY_DRAFT","requiresConfirmation":true,"payload":{"title":"Academia","date":"2026-09-14","startTime":"16:00","durationMinutes":60},"explanation":"incluir"}""" to true,
            """{"commandType":"CHANGE_ACTIVITY","requiresConfirmation":true,"payload":{"activityId":"a1","changes":{"title":"Academia"}},"explanation":"alterar"}""" to true,
            """{"commandType":"REORGANIZE_DAY","requiresConfirmation":true,"payload":{"date":"2026-09-14","reason":"reorganizar"},"explanation":"reorganizar"}""" to true,
            """{"commandType":"MISSING_INFORMATION","requiresConfirmation":false,"payload":{"missingFields":["activityId"]},"explanation":"faltam dados"}""" to true,
            """{"commandType":"MARK_DELAYED","requiresConfirmation":true,"payload":{"activityId":"a1"},"explanation":"faltando delay"}""" to false,
            """{"commandType":"CANCEL_ACTIVITY","requiresConfirmation":true,"payload":{"activityId":"a1","reason":"x"},"explanation":"campo extra"}""" to false,
            """{"commandType":"CHANGE_ACTIVITY","requiresConfirmation":true,"payload":{"activityId":"a1","changes":{}},"explanation":"sem mudanças"}""" to false,
            """{"commandType":"MISSING_INFORMATION","requiresConfirmation":false,"payload":{},"explanation":"faltam dados"}""" to false)
        cases.forEachIndexed{i,(raw,valid)->if(valid){val r=AiCapabilityService(FakeGenerator(raw)).command(CommandRequest(message="pedido"),"req-command-$i");assertEquals(raw.substringAfter("\"commandType\":\"").substringBefore("\""),r.result["commandType"]?.toString()?.trim('"'))}else assertFailsWith<InvalidAiCapabilityException>{AiCapabilityService(FakeGenerator(raw)).command(CommandRequest(message="pedido"),"req-command-$i")}}
    }
    @Test fun insights_must_reference_supplied_evidence(){assertFailsWith<InvalidAiCapabilityException>{AiCapabilityService(FakeGenerator("""{"insights":[{"title":"Carga","description":"alta","evidence":["inventada"],"confidence":"HIGH"}],"recommendations":[]}""")).insight(InsightRequest(evidence=listOf("carga real"),sampleSize=20),"req-insight-grounding")}}
    @Test fun insufficient_insight_sample_requires_low_confidence(){assertFailsWith<InvalidAiCapabilityException>{AiCapabilityService(FakeGenerator("""{"insights":[{"title":"Carga","description":"padrão inicial","evidence":["carga real"],"confidence":"HIGH"}],"recommendations":[]}""")).insight(InsightRequest(evidence=listOf("carga real"),sampleSize=2),"req-insight-small")}}
    @Test fun insufficient_insight_sample_must_not_emit_recommendations(){assertFailsWith<InvalidAiCapabilityException>{AiCapabilityService(FakeGenerator("""{"insights":[],"recommendations":["otimize sua rotina"]}""")).insight(InsightRequest(evidence=listOf("carga real"),sampleSize=2),"req-insight-small-recommendation")}}
    @Test fun sufficient_insight_sample_can_use_higher_confidence(){val r=AiCapabilityService(FakeGenerator("""{"insights":[{"title":"Carga","description":"padrão consistente","evidence":["carga real"],"confidence":"HIGH"}],"recommendations":["revisar distribuição"]}""")).insight(InsightRequest(evidence=listOf("carga real"),sampleSize=10),"req-insight-strong");assertEquals("HIGH",(r.result["insights"] as kotlinx.serialization.json.JsonArray)[0].toString().let{if(it.contains("HIGH"))"HIGH" else "LOW"})}
    @Test fun preference_must_reference_supplied_observations(){assertFailsWith<InvalidAiCapabilityException>{AiCapabilityService(FakeGenerator("""{"preferences":[{"preference":"manhã","evidence":["inventado"],"confidence":"HIGH"}]}""")).preference(PreferenceRequest(observations=listOf("treina às 08h")),"req-pref-grounding")}}
    @Test fun insufficient_preference_observations_require_low_confidence(){assertFailsWith<InvalidAiCapabilityException>{AiCapabilityService(FakeGenerator("""{"preferences":[{"preference":"manhã","evidence":["treina às 08h"],"confidence":"HIGH"}]}""")).preference(PreferenceRequest(observations=listOf("treina às 08h")),"req-pref-small")}}
    @Test fun duplicate_preference_observations_cannot_support_high_confidence(){assertFailsWith<InvalidAiCapabilityException>{AiCapabilityService(FakeGenerator("""{"preferences":[{"preference":"manhã","evidence":["treina cedo"],"confidence":"HIGH"}]}""")).preference(PreferenceRequest(observations=listOf("treina cedo","treina cedo","treina cedo")),"req-pref-duplicates")}}
    @Test fun sufficient_preference_evidence_is_accepted(){val r=AiCapabilityService(FakeGenerator("""{"preferences":[{"preference":"manhã","evidence":["treina às 08h","corre às 07h","estuda às 09h"],"confidence":"HIGH"}]}""")).preference(PreferenceRequest(observations=listOf("treina às 08h","corre às 07h","estuda às 09h")),"req-pref-strong");assertEquals("HIGH",r.result["preferences"].toString().let{if(it.contains("HIGH"))"HIGH" else "LOW"})}
    @Test fun scenario_changes_must_be_explicitly_hypothetical(){assertFailsWith<InvalidAiCapabilityException>{AiCapabilityService(FakeGenerator("""{"scenario":"se eu cancelar academia","changes":[{"field":"status","from":"scheduled","to":"cancelled","hypothetical":false}],"assumptions":[],"inferredFields":[],"requiresClarification":false}""")).scenario(ScenarioRequest(question="e se eu cancelar?"),"req-scenario-hypothesis")}}
    @Test fun scenario_contract_separates_hypothesis_and_inferred_fields(){val r=AiCapabilityService(FakeGenerator("""{"scenario":"se eu atrasar 40 minutos","changes":[{"field":"startTime","from":"18:00","to":"18:40","hypothetical":true}],"assumptions":["a atividade permanece no mesmo dia"],"inferredFields":["newStartTime"],"requiresClarification":false}""")).scenario(ScenarioRequest(question="e se eu atrasar 40 minutos?"),"req-scenario-valid");assertEquals("false",r.result["requiresClarification"].toString())}
    @Test fun ambiguous_scenario_cannot_choose_a_change(){assertFailsWith<InvalidAiCapabilityException>{AiCapabilityService(FakeGenerator("""{"scenario":"e se eu mudar isso?","changes":[{"field":"startTime","from":"18:00","to":"19:00","hypothetical":true}],"assumptions":[],"inferredFields":["targetActivity"],"requiresClarification":true}""")).scenario(ScenarioRequest(question="e se eu mudar isso?"),"req-scenario-ambiguous")}}
    @Test fun ambiguous_scenario_can_request_clarification_without_changes(){val r=AiCapabilityService(FakeGenerator("""{"scenario":"e se eu mudar isso?","changes":[],"assumptions":[],"inferredFields":["targetActivity"],"requiresClarification":true}""")).scenario(ScenarioRequest(question="e se eu mudar isso?"),"req-scenario-clarification");assertEquals("true",r.result["requiresClarification"].toString());assertEquals("[]",r.result["changes"].toString())}
    @Test fun scenario_supports_multiple_hypothetical_changes(){val r=AiCapabilityService(FakeGenerator("""{"scenario":"trabalhar presencialmente na sexta e treinar depois","changes":[{"field":"workLocation","from":"remote","to":"onsite","hypothetical":true},{"field":"activity.startTime","from":"18:00","to":"19:30","hypothetical":true}],"assumptions":["o trabalho presencial termina antes do treino"],"inferredFields":["workLocation","activity.startTime"],"requiresClarification":false}""")).scenario(ScenarioRequest(question="e se eu trabalhar presencialmente na sexta e treinar depois?"),"req-scenario-multiple");assertEquals(2,(r.result["changes"] as kotlinx.serialization.json.JsonArray).size)}
    @Test fun ambiguous_scenario_requires_clarification_without_changes(){val r=AiCapabilityService(FakeGenerator("""{"scenario":"e se eu mudar isso?","changes":[],"assumptions":[],"inferredFields":["targetActivity"],"requiresClarification":true}""")).scenario(ScenarioRequest(question="e se eu mudar isso?"),"req-scenario-clarification-duplicate");assertEquals("true",r.result["requiresClarification"].toString())}
    @Test fun next_action_rejects_blocked_dependency_conflict_or_time(){
        val cases=listOf(
            "blocked-dependency" to NextActionCandidate("blocked-dependency",dependenciesSatisfied=false),
            "blocked-conflict" to NextActionCandidate("blocked-conflict",conflictFree=false),
            "too-long" to NextActionCandidate("too-long",fitsAvailableTime=false),
        )
        cases.forEachIndexed{index,(id,fact)->
            val context=NextActionContext(evidence=listOf("domain facts"),candidateFacts=listOf(fact))
            assertFailsWith<InvalidAiCapabilityException>{
                AiCapabilityService(FakeGenerator("""{"recommendedAction":"$id","reason":"chosen","evidence":["domain facts"],"alternatives":[],"confidence":"HIGH","uncertainty":[],"requiresClarification":false}""")).nextAction(NextActionRequest(candidates=listOf(id),context=context),"req-blocked-$index")
            }
        }
    }
    @Test fun next_action_without_context_is_conservative(){
        val response=AiCapabilityService(FakeGenerator("""{"recommendedAction":null,"reason":"Não há contexto suficiente.","evidence":[],"alternatives":[],"confidence":"LOW","uncertainty":["janela disponível e estado dos candidatos não informados"],"requiresClarification":true}""")).nextAction(NextActionRequest(candidates=listOf("a","b")),"req-no-context")
        assertEquals("LOW",response.result["confidence"]?.toString()?.trim('"'))
        assertEquals("true",response.result["requiresClarification"]?.toString())
    }
    @Test fun next_action_with_null_recommendation_requires_clarification(){
        assertFailsWith<InvalidAiCapabilityException>{AiCapabilityService(FakeGenerator("""{"recommendedAction":null,"reason":"não sei","evidence":[],"alternatives":[],"confidence":"HIGH","uncertainty":[],"requiresClarification":false}""")).nextAction(NextActionRequest(candidates=listOf("a"),context=NextActionContext(evidence=listOf("fact"))),"req-null-recommendation")}
    }
    @Test fun next_action_evidence_must_be_grounded(){
        assertFailsWith<InvalidAiCapabilityException>{AiCapabilityService(FakeGenerator("""{"recommendedAction":"a","reason":"chosen","evidence":["invented"],"alternatives":[],"confidence":"HIGH","uncertainty":[],"requiresClarification":false}""")).nextAction(NextActionRequest(candidates=listOf("a"),context=NextActionContext(evidence=listOf("real"))),"req-next-evidence")}
    }
    @Test fun natural_language_requires_confirmation_or_missing_information(){
        assertFailsWith<InvalidAiCapabilityException>{AiCapabilityService(FakeGenerator("""{"commandType":"CREATE_ACTIVITY_DRAFT","explanation":"criar","requiresConfirmation":false,"payload":{"title":"Academia"},"inferredFields":[],"missingFields":[]}""")).naturalLanguage(NaturalLanguageRequest(message="criar academia"),"req-nl-confirmation")}
        val r=AiCapabilityService(FakeGenerator("""{"commandType":"MISSING_INFORMATION","explanation":"faltam dados","requiresConfirmation":false,"payload":{},"inferredFields":[],"missingFields":["horário"]}""")).naturalLanguage(NaturalLanguageRequest(message="organizar"),"req-nl-missing");assertEquals("MISSING_INFORMATION",r.result["commandType"]?.toString()?.trim('"'))
    }
    @Test fun oversized_inputs_are_rejected(){assertFailsWith<IllegalArgumentException>{AiCapabilityService(FakeGenerator("{} ")).naturalLanguage(NaturalLanguageRequest(message="x".repeat(12001)),"req-big")}}
    @Test fun provider_independent_serialization_uses_structured_request(){val r=AiCapabilityService(FakeGenerator("""{"recommendedAction":null,"reason":"faltam fatos","evidence":[],"alternatives":[],"confidence":"LOW","uncertainty":["contexto"],"requiresClarification":true}""")).nextAction(NextActionRequest(context=NextActionContext(evidence=listOf("contexto"))),"req-serialization");assertFalse(r.model.isBlank())}
}