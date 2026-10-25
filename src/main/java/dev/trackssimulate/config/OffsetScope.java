package dev.trackssimulate.config;

/** Explicit push scope for every setting in the editor. */
public enum OffsetScope {
    WHEEL, ROLE, ASSEMBLY;
    public OffsetScope next(){return values()[(ordinal()+1)%values().length];}
    public OffsetScope next(boolean linked){return !linked&&this==ROLE?WHEEL:next();}
    public boolean includes(boolean sameWheel,boolean sameRole) {
        return this==ASSEMBLY||this==ROLE&&sameRole||this==WHEEL&&sameWheel;
    }
    public String translationKey(){return "screen.trackssimulate.offset_scope."+name().toLowerCase(java.util.Locale.ROOT);}
}
