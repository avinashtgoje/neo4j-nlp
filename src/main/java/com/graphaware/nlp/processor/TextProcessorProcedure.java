/*
 * Copyright (c) 2013-2016 GraphAware
 *
 * This file is part of the GraphAware Framework.
 *
 * GraphAware Framework is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details. You should have received a copy of
 * the GNU General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.graphaware.nlp.processor;

import com.graphaware.nlp.domain.AnnotatedText;
import com.graphaware.nlp.domain.Labels;
import com.graphaware.nlp.domain.Properties;
import com.graphaware.nlp.language.LanguageManager;
import com.graphaware.nlp.procedure.NLPProcedure;
import com.graphaware.nlp.processor.stanford.StanfordTextProcessor;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.neo4j.collection.RawIterator;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.helpers.collection.Iterators;
import org.neo4j.kernel.api.exceptions.ProcedureException;
import org.neo4j.kernel.api.proc.CallableProcedure;
import org.neo4j.kernel.api.proc.Neo4jTypes;
import org.neo4j.kernel.api.proc.ProcedureSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.neo4j.kernel.api.proc.ProcedureSignature.procedureSignature;

public class TextProcessorProcedure extends NLPProcedure {

    private static final Logger LOG = LoggerFactory.getLogger(TextProcessorProcedure.class);

    private final TextProcessor textProcessor;
    private final GraphDatabaseService database;
    private final TextProcessorsManager processorManager;

    private static final String PARAMETER_NAME_TEXT = "text";
    private static final String PARAMETER_NAME_FILTER = "filter";
    private static final String PARAMETER_NAME_ANNOTATED_TEXT = "node";
    private static final String PARAMETER_NAME_ID = "id";
    private static final String PARAMETER_NAME_DEEP_LEVEL = "nlpDepth";
    private static final String PARAMETER_NAME_STORE_TEXT = "store";
    private static final String PARAMETER_NAME_LANGUAGE_CHECK = "languageCheck";
    private static final String PARAMETER_NAME_OUTPUT_TP_CLASS = "class";
    private static final String PARAMETER_NAME_TEXT_PROCESSOR = "textProcessor";

    public TextProcessorProcedure(GraphDatabaseService database, TextProcessorsManager processorManager) {
        this.database = database;
        this.textProcessor = new StanfordTextProcessor();
        this.processorManager = processorManager;
    }

    public CallableProcedure.BasicProcedure annotate() {
        return new CallableProcedure.BasicProcedure(procedureSignature(getProcedureName("annotate"))
                .mode(ProcedureSignature.Mode.READ_WRITE)
                .in(PARAMETER_NAME_INPUT, Neo4jTypes.NTMap)
                .out(PARAMETER_NAME_INPUT_OUTPUT, Neo4jTypes.NTNode).build()) {

            @Override
            public RawIterator<Object[], ProcedureException> apply(Context ctx, Object[] input) throws ProcedureException {

                try {
                    checkIsMap(input[0]);
                    Map<String, Object> inputParams = (Map) input[0];
                    String text = (String) inputParams.get(PARAMETER_NAME_TEXT);
                    boolean checkForLanguage = (Boolean) inputParams.getOrDefault(PARAMETER_NAME_LANGUAGE_CHECK, true);
                    LOG.info("Text: " + text);
                    if (text == null || (checkForLanguage && !LanguageManager.getInstance().isTextLanguageSupported(text))) {
                        LOG.info("text is null or language not supported or unable to detect the language");
                        return Iterators.asRawIterator(Collections.<Object[]>emptyIterator());
                    }
                    Object id = inputParams.get(PARAMETER_NAME_ID);
                    int level = ((Long) inputParams.getOrDefault(PARAMETER_NAME_DEEP_LEVEL, 0l)).intValue();
                    boolean store = (Boolean) inputParams.getOrDefault(PARAMETER_NAME_STORE_TEXT, true);
                    Node annotatedText = checkIfExist(id);
                    if (annotatedText == null) {
                        AnnotatedText annotateText = textProcessor.annotateText(text, id, level, store);
                        annotatedText = annotateText.storeOnGraph(database);
                    }
                    return Iterators.asRawIterator(Collections.<Object[]>singleton(new Object[]{annotatedText}).iterator());
                } catch (Exception ex) {
                    LOG.error("Error while annotating", ex);
                    throw ex;
                }
            }

            private Node checkIfExist(Object id) {
                if (id != null) {
                    ResourceIterator<Node> findNodes = database.findNodes(Labels.AnnotatedText, Properties.PROPERTY_ID, id);
                    if (findNodes.hasNext()) {
                        return findNodes.next();
                    }
                }
                return null;
            }
        };
    }

    public CallableProcedure.BasicProcedure language() {
        return new CallableProcedure.BasicProcedure(procedureSignature(getProcedureName("language"))
                .mode(ProcedureSignature.Mode.READ_WRITE)
                .in(PARAMETER_NAME_INPUT, Neo4jTypes.NTMap)
                .out(PARAMETER_NAME_INPUT_OUTPUT, Neo4jTypes.NTString).build()) {

            @Override
            public RawIterator<Object[], ProcedureException> apply(CallableProcedure.Context ctx, Object[] input) throws ProcedureException {
                checkIsMap(input[0]);
                Map<String, Object> inputParams = (Map) input[0];
                String text = (String) inputParams.get(PARAMETER_NAME_TEXT);
                String language = LanguageManager.getInstance().detectLanguage(text);
                return Iterators.asRawIterator(Collections.<Object[]>singleton(new Object[]{language}).iterator());
            }
        };
    }

    public CallableProcedure.BasicProcedure filter() {
        return new CallableProcedure.BasicProcedure(procedureSignature(getProcedureName("filter"))
                .mode(ProcedureSignature.Mode.READ_WRITE)
                .in(PARAMETER_NAME_INPUT, Neo4jTypes.NTMap)
                .out(PARAMETER_NAME_INPUT_OUTPUT, Neo4jTypes.NTBoolean).build()) {

            @Override
            public RawIterator<Object[], ProcedureException> apply(CallableProcedure.Context ctx, Object[] input) throws ProcedureException {
                checkIsMap(input[0]);
                Map<String, Object> inputParams = (Map) input[0];
                String text = (String) inputParams.get(PARAMETER_NAME_TEXT);
                if (text == null || !LanguageManager.getInstance().isTextLanguageSupported(text)) {
                    LOG.info("text is null or language not supported or unable to detect the language");
                    return Iterators.asRawIterator(Collections.<Object[]>emptyIterator());
                }
                String filter = (String) inputParams.get(PARAMETER_NAME_FILTER);
                if (filter == null) {
                    throw new RuntimeException("A filter value needs to be provided");
                }
                AnnotatedText annotatedText = textProcessor.annotateText(text, 0, 0, false);
                return Iterators.asRawIterator(Collections.<Object[]>singleton(new Object[]{annotatedText.filter(filter)}).iterator());
            }
        };
    }

    public CallableProcedure.BasicProcedure sentiment() {
        return new CallableProcedure.BasicProcedure(procedureSignature(getProcedureName("sentiment"))
                .mode(ProcedureSignature.Mode.READ_WRITE)
                .in(PARAMETER_NAME_INPUT, Neo4jTypes.NTMap)
                .out(PARAMETER_NAME_INPUT_OUTPUT, Neo4jTypes.NTNode).build()) {

            @Override
            public RawIterator<Object[], ProcedureException> apply(CallableProcedure.Context ctx, Object[] input) throws ProcedureException {
                checkIsMap(input[0]);
                Map<String, Object> inputParams = (Map) input[0];
                Node annotatedNode = (Node) inputParams.get(PARAMETER_NAME_ANNOTATED_TEXT);
                AnnotatedText annotatedText = AnnotatedText.load(annotatedNode);
                annotatedText = textProcessor.sentiment(annotatedText);
                annotatedText.storeOnGraph(database);
                return Iterators.asRawIterator(Collections.<Object[]>singleton(new Object[]{annotatedNode}).iterator());
            }
        };
    }

    public CallableProcedure.BasicProcedure getProcessors() {
        return new CallableProcedure.BasicProcedure(procedureSignature(getProcedureName("getProcessors"))
                .mode(ProcedureSignature.Mode.READ_WRITE)
                .out(PARAMETER_NAME_OUTPUT_TP_CLASS, Neo4jTypes.NTString)
                .build()) {

            @Override
            public RawIterator<Object[], ProcedureException> apply(CallableProcedure.Context ctx, Object[] input) throws ProcedureException {
                Set<String> textProcessors = processorManager.getTextProcessors();
                Set<Object[]> result = new HashSet<>();
                textProcessors.forEach(row -> {
                    result.add(new Object[]{row});
                });
                return Iterators.asRawIterator(result.iterator());
            }
        };
    }
    
    public CallableProcedure.BasicProcedure getPipelines() {
        return new CallableProcedure.BasicProcedure(procedureSignature(getProcedureName("getPipelines"))
                .mode(ProcedureSignature.Mode.READ_WRITE)
                .in(PARAMETER_NAME_INPUT, Neo4jTypes.NTMap)
                .out(PARAMETER_NAME_INPUT_OUTPUT, Neo4jTypes.NTString)
                .build()) {

            @Override
            public RawIterator<Object[], ProcedureException> apply(CallableProcedure.Context ctx, Object[] input) throws ProcedureException {
                checkIsMap(input[0]);
                Map<String, Object> inputParams = (Map) input[0];
                String textProcessor = (String) inputParams.get(PARAMETER_NAME_TEXT_PROCESSOR);
                TextProcessor textProcessorInstance = processorManager.getTextProcessor(textProcessor);
                Set<Object[]> result = new HashSet<>();
                List<String> pipelines = textProcessorInstance.getPipelines();
                pipelines.forEach(row -> {
                    result.add(new Object[]{row});
                });
                return Iterators.asRawIterator(result.iterator());
            }
        };
    }
}
