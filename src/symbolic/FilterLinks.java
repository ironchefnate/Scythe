package symbolic;

import util.Pair;

import java.util.*;
import java.util.logging.Filter;

/**
 * This class is used to store the link
 * Created by clwang on 4/20/16.
 */
public class FilterLinks {

    Map<Pair<AbstractSymbolicTable, SymbolicFilter>,
            Set<Set<Pair<AbstractSymbolicTable, SymbolicFilter>>>> links = new HashMap<>();

    public void addLink(Set<Pair<AbstractSymbolicTable, SymbolicFilter>> srcSet,
                        Pair<AbstractSymbolicTable, SymbolicFilter> dst) {
        if (links.containsKey(dst)) {
            links.get(dst).add(srcSet);
        } else {
            Set<Set<Pair<AbstractSymbolicTable, SymbolicFilter>>> sources = new HashSet<>();
            sources.add(srcSet);
            links.put(dst, sources);
        }
    }

    public Set<Set<Pair<AbstractSymbolicTable, SymbolicFilter>>> retrieveSource(
            Pair<AbstractSymbolicTable, SymbolicFilter> dst) {
        return this.links.get(dst);
    }

    public static FilterLinks merge(List<FilterLinks> filterLinks) {
        FilterLinks result = new FilterLinks();
        for (FilterLinks fl : filterLinks) {
            result.links.putAll(fl.links);
        }
        return result;
    }

}
