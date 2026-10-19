package com.llll.rag.search.retriever;

import com.llll.rag.search.model.RetrieveResult;

import java.util.List;

public interface VectorRetriever {

    List<RetrieveResult> retrieve(float[] queryVector, int topK);
}
