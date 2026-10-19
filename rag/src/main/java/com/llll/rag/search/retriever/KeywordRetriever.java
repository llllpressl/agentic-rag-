package com.llll.rag.search.retriever;

import com.llll.rag.search.model.RetrieveResult;

import java.util.List;

public interface KeywordRetriever {

    List<RetrieveResult> retrieve(String query, int topK);
}
