package com.llll.rag.search.retriever;

import com.llll.rag.search.model.RetrieveResult;
import com.llll.rag.search.routing.model.RetrievalScope;

import java.util.List;

public interface VectorRetriever {

    List<RetrieveResult> retrieve(float[] queryVector, int topK);

    List<RetrieveResult> retrieve(float[] queryVector, int topK, RetrievalScope scope);
}
