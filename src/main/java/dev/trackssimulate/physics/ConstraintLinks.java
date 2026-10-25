package dev.trackssimulate.physics;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Local adjacency only. Ground is deliberately not represented as a graph node. */
public final class ConstraintLinks<T> {
    private final Map<T,Set<Edge<T>>> links=new HashMap<>();
    private final ArrayDeque<Edge<T>> maintenance=new ArrayDeque<>();
    private record Edge<T>(T a,T b,BooleanSupplier valid) {}
    public record Reach<T>(Set<T> nodes,boolean complete) {}
    public void add(T a,T b,BooleanSupplier valid){
        if(a==null||b==null||a.equals(b))return;
        var e=new Edge<>(a,b,valid);links.computeIfAbsent(a,k->new HashSet<>()).add(e);links.computeIfAbsent(b,k->new HashSet<>()).add(e);maintenance.add(e);
    }
    private void remove(Edge<T> e){for(T n:List.of(e.a,e.b)){var set=links.get(n);if(set!=null){set.remove(e);if(set.isEmpty())links.remove(n);}}}
    public void prune(int budget){for(int i=0,n=Math.min(budget,maintenance.size());i<n;i++){var e=maintenance.remove();if(e.valid.getAsBoolean())maintenance.add(e);else remove(e);}}
    public Reach<T> from(T root,int maxNodes,int maxEdges){
        var seen=new HashSet<T>();var queue=new ArrayDeque<T>();seen.add(root);queue.add(root);int edges=0;
        while(!queue.isEmpty())for(var e:links.getOrDefault(queue.remove(),Set.of())) {
            if(++edges>maxEdges)return new Reach<>(Set.copyOf(seen),false);
            if(!e.valid.getAsBoolean())continue;
            for(T n:List.of(e.a,e.b))if(!seen.contains(n)) {
                if(seen.size()>=maxNodes)return new Reach<>(Set.copyOf(seen),false);
                seen.add(n);queue.add(n);
            }
        }
        return new Reach<>(Set.copyOf(seen),true);
    }
}
