package forward_enumeration.table_enumerator;

import forward_enumeration.context.EnumContext;
import forward_enumeration.container.QueryContainer;
import forward_enumeration.primitive.AggrEnumerator;
import forward_enumeration.primitive.LeftJoinEnumerator;
import global.GlobalConfig;
import backward_inference.MappingInference;
import sql.lang.Table;
import sql.lang.ast.Environment;
import sql.lang.ast.filter.EmptyFilter;
import sql.lang.ast.table.*;
import sql.lang.ast.val.NamedVal;
import sql.lang.exception.SQLEvalException;
import summarytable.*;
import util.Pair;
import util.RenameTNWrapper;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Created by clwang on 3/28/16.
 * The enumeration algorithm with abstract table representation.
 */
public class StagedEnumerator extends AbstractTableEnumerator {

    // this is a helper to print SQL queries
    Set<AbstractSummaryTable> countPrinted = new HashSet<>();

    @Override
    public List<TableNode> enumTable(EnumContext ec, int maxDepth) {


        // this is the list to store all summary tables used in enumeration
        List<AbstractSummaryTable> summaryTables = new ArrayList<>();

        // construct symbolic table for each named table.
        QueryContainer candidateCollector = new QueryContainer(QueryContainer.ContainerType.SummaryTableWBV);

        // build symbolic table for each input table, and store them in SymTables
        List<AbstractSummaryTable> inputSummary = EnumerationModules.enumFromInputTables(ec);
        summaryTables.addAll(inputSummary);
        System.out.println("[Basic]: Candidates: " + candidateCollector.getMemoizedTables().size() + " [SymTableForInputs]: Intermediate: " + inputSummary.size());


        List<AbstractSummaryTable> aggrSummary = EnumerationModules.enumAggregation(inputSummary, ec);
        summaryTables.addAll(aggrSummary);
        System.out.println("[Aggregation]: " + aggrSummary.size() + " [SymTable]: " + summaryTables.size());


        // only contains symbolic table generated from last stage
        //  (here includes those from aggr and input)
        List<AbstractSummaryTable> basicAndAggr = new ArrayList<>();
        basicAndAggr.addAll(summaryTables);

        List<AbstractSummaryTable> stFromLastStage = summaryTables;

        tryEvalToOutput(stFromLastStage, ec, candidateCollector);

        List<AbstractSummaryTable> joinSummary = new ArrayList<>();

        // Synthesis of JOIN, can be up to 2 levels of nested joins
        for (int i = 1; i <= maxDepth; i ++) {

            joinSummary = EnumerationModules.enumJoin(stFromLastStage, inputSummary);
            summaryTables.addAll(joinSummary);
            stFromLastStage = joinSummary;
            System.out.println("[EnumJoin] level " + i + " [SymTable]: " + summaryTables.size());

            tryEvalToOutput(stFromLastStage, ec, candidateCollector);

            if (candidateCollector.getAllCandidates().size() > 0)
                break;
        }

        // Try enumerating joining two tables from left-join
        if (candidateCollector.getAllCandidates().size() == 0) {
            stFromLastStage = inputSummary;
            for (int i = 1; i <= maxDepth; i ++) {
                List<AbstractSummaryTable> leftJoinSummary = EnumerationModules.enumLeftJoin(stFromLastStage, inputSummary, ec);
                summaryTables.addAll(leftJoinSummary);
                tryEvalToOutput(leftJoinSummary, ec, candidateCollector);

                if (candidateCollector.getAllCandidates().size() > 0)
                    break;

                stFromLastStage = leftJoinSummary;
            }
        }

        // Try enumerating by joining two tables from aggregation
        if (candidateCollector.getAllCandidates().size() == 0) {
            stFromLastStage = basicAndAggr;
            for (int i = 1; i <= maxDepth; i ++) {
                List<AbstractSummaryTable> tmp = EnumerationModules.enumJoin(stFromLastStage, basicAndAggr);

                summaryTables.addAll(tmp);
                stFromLastStage = tmp;
                System.out.println("[EnumJoinOnAggr] level " + i + " [SymTable]: " + summaryTables.size());

                tryEvalToOutput(stFromLastStage, ec, candidateCollector);

                if (candidateCollector.getAllCandidates().size() > 0)
                    break;
            }
        }

        // Enumerate aggregation on joined tables
        if (candidateCollector.getAllCandidates().size() == 0) {
            List<AbstractSummaryTable> simpleJoinSummary = inputSummary;
            for (int i = 1; i <= maxDepth-1; i ++) {
                simpleJoinSummary.addAll(EnumerationModules.enumJoin(simpleJoinSummary, inputSummary));
            }
            List<AbstractSummaryTable> aggrOnJoinSummary  = EnumerationModules.enumAggregation(simpleJoinSummary, ec);
            tryEvalToOutput(aggrOnJoinSummary, ec, candidateCollector);
        }

        if (candidateCollector.getAllCandidates().size() == 0) {
            List<AbstractSummaryTable> aggrAggrSummary = EnumerationModules.enumAggregation(aggrSummary, ec);
            for (int i = 1; i <= maxDepth; i ++) {
                List<AbstractSummaryTable> tmp = EnumerationModules.enumJoin(aggrAggrSummary, basicAndAggr);
                tryEvalToOutput(tmp, ec, candidateCollector);
                aggrAggrSummary = tmp;
            }
        }

        System.out.println("ASymTable Enumeration done: " + (summaryTables.size()));

        return decodingToQueries(candidateCollector, ec);
    }

    static class EnumerationModules {

        public static List<AbstractSummaryTable> enumFromInputTables(EnumContext ec) {
            return ec.getInputs()
                    .stream().map(t -> new PrimitiveSummaryTable(t, new NamedTable(t)))
                    .collect(Collectors.toList());
        }

        public static List<AbstractSummaryTable> enumAggregation(List<AbstractSummaryTable> stList, EnumContext ec) {

            List<AbstractSummaryTable> result = new ArrayList<>();

            for (AbstractSummaryTable st : stList) {

                // core tables to be used in enumerate aggregation
                List<Pair<Table, BVFilter>> instantiated = st.instantiatedAllTablesWithBVFilters(ec);

                for (Pair<Table, BVFilter> p : instantiated) {
                    // enumerating aggregation queries,
                    // the core of each query is built atop a filtering clause on an input table
                    ec.setTableNodes(Arrays.asList(new NamedTable(p.getKey())));
                    List<TableNode> ans = AggrEnumerator.enumAggrFromEC(ec, GlobalConfig.SIMPLIFY_AGGR_FIELD);
                    for (TableNode an : ans) {
                        try {
                            // build symbolic tables out of aggregation table nodes, and store them for next stage enumeration
                            result.add(new PrimitiveSummaryTable(an.eval(new Environment()), an, Arrays.asList(new Pair<>(st, p.getValue()))));
                        } catch (SQLEvalException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
            return result;
        }

        public static List<AbstractSummaryTable> enumJoin(
                List<AbstractSummaryTable> leftStList,
                List<AbstractSummaryTable> rightStList) {

            // used to collect queries generated in the most recent stage
            List<AbstractSummaryTable> collector = new ArrayList<>();
            for (int k = 0; k < leftStList.size(); k ++) {
                for (int l = 0; l < rightStList.size(); l ++) {

                    AbstractSummaryTable st1 = leftStList.get(k);
                    AbstractSummaryTable st2 = rightStList.get(l);

                    CompoundSummaryTable sct = new CompoundSummaryTable(st1, st2);
                    collector.add(sct);
                }
            }

            return collector;
        }

        public static List<AbstractSummaryTable> enumLeftJoin(
                List<AbstractSummaryTable> leftStList,
                List<AbstractSummaryTable> rightStList,
                EnumContext ec) {

            // we don't filter left table since it will be filtered when inferring filtering on the primitive summary table
            List<Pair<Table, Pair<AbstractSummaryTable, BVFilter>>> leftTablePairs = leftStList.stream()
                    .map(st -> new Pair<>(st.getBaseTable(), new Pair<>(st, BVFilter.genEmptyFilter(st.getBaseTable().getContent().size()))))
                    .collect(Collectors.toList());
            List<Pair<Table, Pair<AbstractSummaryTable, BVFilter>>> rightTablePairs = rightStList.stream()
                    .flatMap(st -> st.instantiatedAllTablesWithBVFilters(ec)
                            .stream()
                            .map(p -> new Pair<>(p.getKey(), new Pair<>(st, p.getValue()))))
                    .collect(Collectors.toList());

            List<AbstractSummaryTable> result = new ArrayList<>();

            for (Pair<Table, Pair<AbstractSummaryTable, BVFilter>> p1 : leftTablePairs) {
                for (Pair<Table, Pair<AbstractSummaryTable, BVFilter>> p2 : rightTablePairs) {

                    List<TableNode> tns =
                            LeftJoinEnumerator
                                .enumLeftJoin(new NamedTable(p1.getKey()), new NamedTable(p2.getKey()))
                                .stream()
                                .map(RenameTNWrapper::tryRename).collect(Collectors.toList());

                    List<Pair<AbstractSummaryTable, BVFilter>> subTree = new ArrayList<>();
                    subTree.add(p1.getValue());
                    subTree.add(p2.getValue());

                    for (TableNode tn : tns) {
                        try {
                            result.add(new PrimitiveSummaryTable(tn.eval(new Environment()), tn, subTree));
                        } catch (SQLEvalException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
            return result;
        }
    }

    public List<TableNode> decodingToQueries(QueryContainer qc, EnumContext ec) {

        List<TableNode> decodeResult = new ArrayList<>();

        // Extract the filters to be decoded for each AbstractSymbolicTable from qc
        Map<AbstractSummaryTable, Set<BVFilter>> filtersToDecode = new HashMap<>();
        for (Pair<AbstractSummaryTable, BVFilter> c : qc.getAllCandidates()) {
            if (! filtersToDecode.containsKey(c.getKey())) {
                filtersToDecode.put(c.getKey(), new HashSet<>());
            }
            filtersToDecode.get(c.getKey()).add(c.getValue());
        }

        // generate candidate symFilterTrees for each candidate
        List<BVFilterCompTree> candidateTrees = new ArrayList<>();
        for (Map.Entry<AbstractSummaryTable, Set<BVFilter>> c : filtersToDecode.entrySet()) {
            Map<BVFilter, List<BVFilterCompTree>> cQuery = c.getKey().batchGenDecomposition(c.getValue());
            for (Map.Entry<BVFilter, List<BVFilterCompTree>> i : cQuery.entrySet()) {
                candidateTrees.addAll(i.getValue());
            }
        }

        System.out.println("Candidate Tree Number: " + candidateTrees.size());

        List<TableNode> treeDecodeResult = new ArrayList<>();
        for (BVFilterCompTree t : candidateTrees) {
            List<TableNode> temp = t.translateToTopSQL(ec);
            treeDecodeResult.addAll(temp);
        }

        treeDecodeResult.sort((x,y) -> (x.estimateAllFilterCost() <= y.estimateAllFilterCost() ?
                (x.estimateAllFilterCost() < y.estimateAllFilterCost() ? -1 : 0) : 1));

        for (TableNode tn : treeDecodeResult) {
            try {
                Table t = tn.eval(new Environment());
                List<Integer> projectionIndexes = Table.inferProjection(t, ec.getOutputTable());

                // with projection result printed
                SelectNode stn;

                if ((tn instanceof SelectNode)) {
                    stn = new SelectNode(
                            projectionIndexes.stream().map(x -> ((SelectNode) tn).getColumns().get(x)).collect(Collectors.toList()),
                            ((SelectNode) tn).getTableNode(),
                            ((SelectNode) tn).getFilter());
                } else {
                    stn = new SelectNode(
                            projectionIndexes.stream().map(x -> new NamedVal(tn.getSchema().get(x))).collect(Collectors.toList()),
                            tn,
                            new EmptyFilter());
                }

                decodeResult.add(stn);
            } catch (SQLEvalException e) {
                e.printStackTrace();
            }
        }

        return decodeResult;
    }

    // The following two methods try to infer whether the output example can be derived from summary tables
    // Upon success, candidates will be stored into candidate collector

    private void tryEvalToOutput(List<AbstractSummaryTable> stList, EnumContext ec, QueryContainer candidateCollector) {
        stList.forEach(st -> tryEvalToOutput(st, ec, candidateCollector));
    }

    // Try to evaluate whether the output table can be derived from symbolic table st.
    private void tryEvalToOutput(AbstractSummaryTable st, EnumContext ec, QueryContainer candidateCollector) {

        BiConsumer<AbstractSummaryTable, BVFilter> f = (symTable, symFilter) -> {

            if (symFilter.getFilterRep().isEmpty())
                return;

            Table t = symTable.getBaseTable().duplicate();
            t.getContent().clear();
            for (Integer i :symFilter.getFilterRep()) {
                t.getContent().add(symTable.getBaseTable().getContent().get(i).duplicate());
            }

            // add the target to qc if the evaluation result can be projection to be output table
            //    1) size equal
            //    2) can generate a trace
            if( t.getContent().size() == ec.getOutputTable().getContent().size()
                    && !MappingInference.buildMapping(t, ec.getOutputTable()).genColumnMappingInstances().isEmpty())
                candidateCollector.insertCandidate(new Pair<>(symTable, symFilter));
        };

        MappingInference mi = MappingInference.buildMapping(st.getBaseTable(), ec.getOutputTable());
        if (! mi.everyCellHasImage())
            return;

        if (GlobalConfig.SPECIAL_TREAT_LAST_STAGE) {
            st.emitFinalVisitAllTables(mi, ec, f);
        } else {
            st.emitInstantiateAllTables(ec, f);
        }
    }

}
