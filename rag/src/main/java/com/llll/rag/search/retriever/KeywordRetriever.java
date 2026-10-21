package com.llll.rag.search.retriever;

import com.llll.rag.search.model.RetrieveResult;
import com.llll.rag.search.routing.model.RetrievalScope;

import java.util.List;

public interface KeywordRetriever {

    List<RetrieveResult> retrieve(String query, int topK);

    List<RetrieveResult> retrieve(String query, int topK, RetrievalScope scope);
}
