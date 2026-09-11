package com.studyos.studio.application;

import com.studyos.learning.application.LearningEvidenceGateway;
import com.studyos.notebook.application.NotebookAccess;
import com.studyos.source.application.SourceLookup;
import com.studyos.studio.application.port.StudioStore;
import com.studyos.studio.domain.QuizScorer;
import com.studyos.shared.outbox.Outbox;
import com.studyos.shared.persistence.Json;
import com.studyos.shared.persistence.Rows;
import com.studyos.shared.web.ApiException;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudioService {
    private final StudioStore store;
    private final NotebookAccess notebooks;
    private final SourceLookup sources;
    private final LearningEvidenceGateway evidence;
    private final Outbox outbox;
    public StudioService(StudioStore store,NotebookAccess notebooks,SourceLookup sources,LearningEvidenceGateway evidence,Outbox outbox){
        this.store=store;this.notebooks=notebooks;this.sources=sources;this.evidence=evidence;this.outbox=outbox;
    }
    @Transactional
    public Map<String,Object> generate(UUID user,UUID notebook,String type,String key,List<UUID> requested,Map<String,Object> options){
        var scope=notebooks.requireWrite(user,notebook);
        if(key==null||key.isBlank()||key.length()>120)throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED","Provide a valid Idempotency-Key.");
        String fingerprint=fingerprint(Map.of("notebook",notebook,"type",type,"sourceIds",requested.stream().sorted().toList(),"options",new TreeMap<>(options)));
        var prior=store.jobByKey(user,key);
        if(prior.isPresent()){
            if(!fingerprint.equals(prior.get().get("requestFingerprint")))throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED","The key belongs to a different artifact request.");
            return jobResponse(prior.get());
        }
        List<UUID> resolved=sources.requireReady(user,notebook,requested);
        if(resolved.isEmpty())throw ApiException.conflict("SOURCE_NOT_READY","Select at least one ready source.");
        var job=store.createJob(user,notebook,type,key,fingerprint,Map.of("sourceIds",resolved),options);
        UUID id=Rows.uuid(job,"id");
        String event=switch(type){case "QUIZ"->"quiz.generate.requested.v1";case "FLASHCARDS"->"flashcards.generate.requested.v1";case "STUDY_GUIDE"->"study-guide.generate.requested.v1";default->throw ApiException.badRequest("VALIDATION_FAILED","Invalid artifact type.");};
        outbox.publish(scope.workspaceId(),"ARTIFACT_JOB",id,event,Map.of("artifactJobId",id,"notebookId",notebook,"userId",user,"scope",Map.of("sourceIds",resolved),"options",options));
        return jobResponse(job);
    }
    public Map<String,Object> job(UUID user,UUID id){var job=store.job(user,id,false).orElseThrow(()->ApiException.notFound("JOB_NOT_FOUND","Job not found."));notebooks.requireRead(user,Rows.uuid(job,"notebookId"));return jobResponse(job);}
    private Map<String,Object> jobResponse(Map<String,Object> job){var result=new LinkedHashMap<String,Object>();result.put("id",job.get("id"));result.put("type",job.get("artifactType"));result.put("status",job.get("status"));result.put("progress","SUCCEEDED".equals(job.get("status"))?100:0);result.put("resultRef",job.get("resultRef"));result.put("errorCode",job.get("errorCode"));result.put("result",job.get("resultJson"));return result;}

    @Transactional
    public void acceptEvent(Map<String,Object> event){
        UUID eventId=ArtifactValidator.uuid(event.get("eventId"));UUID workspace=ArtifactValidator.uuid(event.get("workspaceId"));
        if(!Integer.valueOf(1).equals(event.get("eventVersion")))throw ApiException.badRequest("EVENT_VERSION_UNSUPPORTED","Unsupported event version.");
        var payload=ArtifactValidator.object(event.get("payload"));UUID id=ArtifactValidator.uuid(payload.get("artifactJobId"));
        var job=store.jobForEvent(workspace,id).orElseThrow(()->ApiException.badRequest("ARTIFACT_SCOPE_INVALID","Artifact job unavailable in this workspace."));
        if(!Rows.uuid(job,"notebookId").equals(ArtifactValidator.uuid(payload.get("notebookId")))||!Rows.uuid(job,"userId").equals(ArtifactValidator.uuid(payload.get("userId"))))throw ApiException.badRequest("ARTIFACT_SCOPE_INVALID","Artifact context does not match its job.");
        if(!store.claimEvent(eventId)||Set.of("SUCCEEDED","FAILED","CANCELLED").contains(job.get("status")))return;
        String eventType=Objects.toString(event.get("eventType"));
        if(eventType.equals("artifact.generation.failed.v1")){String code=Objects.toString(payload.get("errorCode"),"ARTIFACT_GENERATION_FAILED");store.failJob(id,code.length()>80?"ARTIFACT_GENERATION_FAILED":code);return;}
        String type=job.get("artifactType").toString();
        String expected=switch(type){case "QUIZ"->"quiz.generated.v1";case "FLASHCARDS"->"flashcards.generated.v1";case "STUDY_GUIDE"->"study-guide.generated.v1";default->"";};
        if(!expected.equals(eventType)||!type.equals(payload.get("artifactType")))throw ApiException.badRequest("ARTIFACT_TYPE_INVALID","Artifact result type does not match its job.");
        Map<String,Object> artifact;
        UUID notebook=Rows.uuid(job,"notebookId"),user=Rows.uuid(job,"userId");
        try {
            var requested=ArtifactValidator.object(job.get("scopeJson"));
            List<UUID> sourceIds=((List<?>)requested.get("sourceIds")).stream().map(ArtifactValidator::uuid).toList();
            artifact=new ArtifactValidator(store).validate(type,ArtifactValidator.object(payload.get("artifact")),workspace,notebook,sourceIds);
        }catch(ApiException e){store.failJob(id,e.code());return;}
        UUID result=switch(type){
            case "QUIZ"->store.saveQuiz(user,notebook,id,artifact.get("title").toString(),ArtifactValidator.objects(artifact.get("questions")));
            case "FLASHCARDS"->store.saveDeck(user,notebook,id,artifact.get("title").toString(),ArtifactValidator.objects(artifact.get("cards")));
            default->id;
        };
        // Never return the quiz answer key through job polling.
        store.completeJob(id,result,type.equals("STUDY_GUIDE")?artifact:Map.of("title",artifact.get("title")));
    }

    public Map<String,Object> quiz(UUID user,UUID id){
        var quiz=ownedQuiz(user,id);var result=new LinkedHashMap<>(quiz);List<Map<String,Object>> publicQuestions=new ArrayList<>();
        for(var question:store.questions(id)){
            var q=new LinkedHashMap<String,Object>();q.put("id",question.get("id"));q.put("type",question.get("questionType"));q.put("prompt",question.get("prompt"));q.put("conceptIds",question.get("conceptIds"));
            var answer=ArtifactValidator.object(question.get("answerJson"));q.put("options",answer.getOrDefault("options",List.of()));publicQuestions.add(q);
        }
        result.put("questions",publicQuestions);return result;
    }
    private Map<String,Object> ownedQuiz(UUID user,UUID id){var q=store.quiz(user,id).orElseThrow(()->ApiException.notFound("QUIZ_NOT_FOUND","Quiz not found."));notebooks.requireRead(user,Rows.uuid(q,"notebookId"));return q;}
    @Transactional public Map<String,Object> start(UUID user,UUID quiz){var q=ownedQuiz(user,quiz);notebooks.requireWrite(user,Rows.uuid(q,"notebookId"));return store.startAttempt(user,quiz);}
    private Map<String,Object> ownedAttempt(UUID user,UUID id,boolean lock){var attempt=store.attempt(user,id,lock).orElseThrow(()->ApiException.notFound("ATTEMPT_NOT_FOUND","Attempt not found."));notebooks.requireRead(user,Rows.uuid(attempt,"notebookId"));return attempt;}
    @Transactional public Map<String,Object> answer(UUID user,UUID id,UUID question,Object answer){
        var attempt=ownedAttempt(user,id,true);notebooks.requireWrite(user,Rows.uuid(attempt,"notebookId"));
        if(!"IN_PROGRESS".equals(attempt.get("status")))throw ApiException.conflict("QUIZ_ATTEMPT_ALREADY_COMPLETED","This attempt is already completed.");
        if(Json.write(answer).length()>10000)throw ApiException.badRequest("VALIDATION_FAILED","Answer is too large.");
        if(store.questions(Rows.uuid(attempt,"quizId")).stream().noneMatch(q->Rows.uuid(q,"id").equals(question)))throw ApiException.badRequest("QUESTION_SCOPE_INVALID","Question does not belong to this attempt.");
        store.saveAnswer(id,question,answer);return Map.of("saved",true,"questionId",question);
    }
    @Transactional public Map<String,Object> complete(UUID user,UUID id){
        var attempt=ownedAttempt(user,id,true);UUID notebook=Rows.uuid(attempt,"notebookId");var scope=notebooks.requireWrite(user,notebook);
        if("COMPLETED".equals(attempt.get("status")))return result(user,id);
        var questions=store.questions(Rows.uuid(attempt,"quizId"));if(questions.isEmpty())throw ApiException.conflict("QUIZ_EMPTY","Quiz has no questions.");
        Map<UUID,Object> answers=new HashMap<>();for(var a:store.answers(id))answers.put(Rows.uuid(a,"questionId"),a.get("answerJson"));
        int correctCount=0;Map<UUID,List<BigDecimal>> conceptScores=new TreeMap<>();
        for(var q:questions){UUID qid=Rows.uuid(q,"id");var key=ArtifactValidator.object(q.get("answerJson"));boolean correct=QuizScorer.correct(q.get("questionType").toString(),key.get("correctAnswer"),answers.get(qid));
            if(correct)correctCount++;store.scoreAnswer(id,qid,correct);
            for(Object c:(List<?>)q.get("conceptIds"))conceptScores.computeIfAbsent(ArtifactValidator.uuid(c),ignored->new ArrayList<>()).add(correct?BigDecimal.ONE:BigDecimal.ZERO);
        }
        BigDecimal score=BigDecimal.valueOf(correctCount).divide(BigDecimal.valueOf(questions.size()),4,RoundingMode.HALF_UP);
        store.completeAttempt(id,score);
        for(var item:conceptScores.entrySet()){var scores=item.getValue();var average=scores.stream().reduce(BigDecimal.ZERO,BigDecimal::add).divide(BigDecimal.valueOf(scores.size()),4,RoundingMode.HALF_UP);
            evidence.record(user,notebook,item.getKey(),"QUIZ",average,new BigDecimal("0.8"),"quiz:"+id,id);
        }
        outbox.publish(scope.workspaceId(),"QUIZ_ATTEMPT",id,"quiz.attempt.completed.v1",Map.of("userId",user,"attemptId",id,"quizId",attempt.get("quizId"),"score",score));return result(user,id);
    }
    public Map<String,Object> result(UUID user,UUID id){
        var attempt=new LinkedHashMap<>(ownedAttempt(user,id,false));var answers=store.answers(id);attempt.put("answers",answers);
        if("COMPLETED".equals(attempt.get("status"))){List<Map<String,Object>> feedback=new ArrayList<>();for(var q:store.questions(Rows.uuid(attempt,"quizId")))feedback.add(Map.of("questionId",q.get("id"),"correctAnswer",ArtifactValidator.object(q.get("answerJson")).get("correctAnswer"),"explanation",Objects.toString(q.get("explanation"),"")));attempt.put("feedback",feedback);}
        return attempt;
    }
    private String fingerprint(Object data){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(data).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
