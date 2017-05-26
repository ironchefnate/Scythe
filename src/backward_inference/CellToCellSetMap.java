package backward_inference;

import java.util.*;

/**
 * Created by clwang on 2/17/16.
 * This is the data structure that maps a cell to a set of cells (cells are represented using their indexes)
 */
public class CellToCellSetMap extends CellMapping<Set<CellIndex>>{

    // initialize the map
    CellToCellSetMap(int maxR, int maxC) {
        super(maxR, maxC, () -> new HashSet<>());
    }

    // add the mapping pair u -> v into the quick map
    public void addPair(CellIndex u, CellIndex v) {
        this.map.get(u.r()).get(u.c()).add(v);
    }

    // remove the mapping pair u -> v from the map
    // TODO: note that this operation is super slow
    public void removePair(CellIndex u, CellIndex v) {
        this.map.get(u.r()).get(u.c()).remove(v);
    }

    @Override
    public boolean equals(Object obj) {
        if (! (obj instanceof CellToCellSetMap))
            return false;
        CellToCellSetMap target = (CellToCellSetMap) obj;
        if (! (this.maxR == target.maxR && this.maxC == target.maxC))
            return false;
        for (int i = 0; i < this.maxR; i ++) {
            for (int j = 0; j < this.maxC; j ++) {
                if (! map.get(i).get(j).equals(target.map.get(i).get(j)))
                    return false;
            }
        }
        return true;
    }

    @Override
    boolean consistencyCheck(CellIndex src, Set<CellIndex> dst) {
        return true;
    }

    @Override
    public CellToCellSetMap copy() {
        CellToCellSetMap ccsm = new CellToCellSetMap(maxR, maxC);
        ccsm.map = new ArrayList<>();
        for (List<Set<CellIndex>> l : this.map) {
            List<Set<CellIndex>> tmp = new ArrayList();
            for (Set<CellIndex> ci : l) {
                Set<CellIndex> s = new HashSet<>();
                for(CellIndex c : ci)
                    s.add(c.copy());
                tmp.add(s);
            }
            ccsm.map.add(tmp);
        }
        return ccsm;
    }
}