package com.studyos.note.application;
import com.studyos.note.application.port.NoteRepository;import com.studyos.notebook.application.NotebookAccess;import com.studyos.shared.persistence.Rows;import com.studyos.shared.web.ApiException;
import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;import java.util.*;
@Service
public class NoteService{
    private final NoteRepository repo;private final NotebookAccess notebooks;public NoteService(NoteRepository repo,NotebookAccess notebooks){this.repo=repo;this.notebooks=notebooks;}
    public Map<String,Object> get(UUID user,UUID id){var row=repo.get(id).filter(r->user.equals(Rows.uuid(r,"userId"))).orElseThrow(()->ApiException.notFound("NOTE_NOT_FOUND","Note not found."));notebooks.requireRead(user,Rows.uuid(row,"notebookId"));return decorate(row);}
    public List<Map<String,Object>> list(UUID user,UUID notebook){notebooks.requireRead(user,notebook);return repo.list(user,notebook).stream().map(this::decorate).toList();}
    @Transactional public Map<String,Object> create(UUID user,UUID notebook,String title,String content,List<UUID> citations){var scope=notebooks.requireWrite(user,notebook);validateCitations(user,notebook,citations);UUID id=repo.create(user,scope.workspaceId(),notebook,title,content);repo.link(id,citations==null?List.of():citations.stream().distinct().toList());return get(user,id);}
    @Transactional public Map<String,Object> update(UUID user,UUID id,String title,String content,List<UUID> citations){var row=get(user,id);UUID notebook=Rows.uuid(row,"notebookId");notebooks.requireWrite(user,notebook);validateCitations(user,notebook,citations);repo.update(id,title,content);if(citations!=null)repo.link(id,citations.stream().distinct().toList());return get(user,id);}
    @Transactional public void delete(UUID user,UUID id){var row=get(user,id);notebooks.requireWrite(user,Rows.uuid(row,"notebookId"));repo.delete(id);}
    private void validateCitations(UUID user,UUID notebook,List<UUID> ids){if(ids!=null)for(UUID id:ids)if(!repo.citationAllowed(id,user,notebook))throw ApiException.badRequest("INVALID_CITATION_SCOPE","Citations must belong to your conversation in this notebook.");}
    private Map<String,Object> decorate(Map<String,Object> row){var result=new LinkedHashMap<>(row);result.put("citationIds",repo.citations(Rows.uuid(row,"id")));return result;}
}

