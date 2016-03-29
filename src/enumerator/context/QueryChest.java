package enumerator.context;

import javafx.util.Pair;
import sql.lang.Table;
import sql.lang.ast.Environment;
import sql.lang.ast.table.NamedTable;
import sql.lang.ast.table.TableNode;
import util.HierarchicalMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Created by clwang on 3/26/16.
 * This class is used to store enumerated queries
 */
public class QueryChest {
    // tabled that is memoized
    private Map<Table, List<TableNode>> memory = new HierarchicalMap<>();

    public Map<Table, List<TableNode>> getMemoizedTables() { return this.memory; }

    private QueryChest() {}
    public static QueryChest initWithInputTables(List<Table> input) {
        QueryChest qc = new QueryChest();
        qc.updateQueries(input.stream().map(t -> new NamedTable(t)).collect(Collectors.toList()));
        return qc;
    }

    // update the QueryChest adding new tables
    // (these new tables will be used later)
    public void updateQueries(List<TableNode> queries) {
        for (TableNode tn : queries) {
            try {
                Table t = tn.eval(new Environment());

                if (t.getContent().size() == 0)
                    continue;

                if (memory.containsKey(t)) {
                    memory.get(t).add(tn);
                    //System.out.println("~" + memory.get(t).size());
                } else {
                    ArrayList<TableNode> ar = new ArrayList<>();
                    ar.add(tn);
                    //System.out.println("[Query Chest 49] QC memory size: " + memory.size());
                    memory.put(t, ar);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public List<TableNode> lookup(Table t) {
        if (memory.containsKey(t))
            return memory.get(t);
        else return new ArrayList<>();
    }

    public List<TableNode> getRepresentativeTableNodes() {
        return this.memory.keySet().stream().map(t -> new NamedTable(t)).collect(Collectors.toList());
    }

    /**
     * Export a table into a list of tables by unfolding intermediate results with memoized structures
     * @param tn the table node to be unfolded
     * @param alreadyLookedUp the set of named tables that are already used in unfolding
     * @param inputNamedTables the input table for the enumeration process, which determines the when to stop unfolding
     * @return the set of queries that are equivalent to the input tn after unfolding
     */
    public List<TableNode> export(TableNode tn, List<NamedTable> alreadyLookedUp, List<NamedTable> inputNamedTables) {

        List<TableNode> result = new ArrayList<>();

        List<NamedTable> namedTables = tn.namedTableInvolved();

        if (namedTables.isEmpty())
            return Arrays.asList(tn);

        List<NamedTable> tableToSubst = new ArrayList<>();

        for (NamedTable nt : namedTables) {
            boolean contained = false;
            for (NamedTable it : inputNamedTables) {
                if (it.getTable().contentEquals(nt.getTable())) {
                    contained = true;
                }
            }
            if (contained == false) {
                tableToSubst.add(nt);
            }
        }

        if (tableToSubst.isEmpty()) {
            // does not contain any other intermediate tables
            return Arrays.asList(tn);
        }

        for (NamedTable nt : namedTables) {
            for (NamedTable alt : alreadyLookedUp) {
                if (alt.getTable().contentEquals(nt.getTable()))
                    return Arrays.asList();
            }
        }

        List<NamedTable> newAlreadyLookedUp = new ArrayList<>();
        newAlreadyLookedUp.addAll(alreadyLookedUp);
        newAlreadyLookedUp.addAll(namedTables);

        List<List<TableNode>> targetedMaps = new ArrayList<>();
        for (NamedTable nt : tableToSubst) {
            List<TableNode> lkupResult = this.lookup(nt.getTable());
            List<TableNode> candidatesForNt = new ArrayList<>();
            lkupResult.stream().forEach(
                    lkupTn -> candidatesForNt.addAll(
                            this.export(lkupTn,
                                    newAlreadyLookedUp.stream().distinct().collect(Collectors.toList()),
                                    inputNamedTables)));
            targetedMaps.add(candidatesForNt);
        }

        List<List<TableNode>> ImageSet = product(targetedMaps);

        for (List<TableNode> oneMap : ImageSet) {
            List<Pair<TableNode, TableNode>> substPair = new ArrayList<>();
            for (int i = 0; i < oneMap.size(); i ++) {
                substPair.add(new Pair<>(tableToSubst.get(i), oneMap.get(i)));
            }
            result.add(tn.tableSubst(substPair));
        }

        return result;
    }

    // given a list of tables, calculate the cartesian product of these tables
    public static List<List<TableNode>> product(List<List<TableNode>> tns) {
        List<List<TableNode>> result = new ArrayList<>();

        if (tns.size() == 1) {
            for (TableNode tn : tns.get(0)) {
                result.add(Arrays.asList(tn));
            }
            return result;
        }

        List<List<TableNode>> tailProd = product(tns.subList(1, tns.size()));
        for (TableNode tn : tns.get(0)) {
            for (List<TableNode> tailTN : tailProd) {
                ArrayList<TableNode> temp = new ArrayList();
                temp.add(tn);
                temp.addAll(tailTN);
                result.add(temp);
            }
        }
        return result;
    }

}