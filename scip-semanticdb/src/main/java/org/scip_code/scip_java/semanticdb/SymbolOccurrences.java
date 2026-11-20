package org.scip_code.scip_java.semanticdb;

import java.util.ArrayList;
import java.util.List;

public class SymbolOccurrences {
  public List<Semanticdb.SymbolOccurrence> occurrences = new ArrayList<>();

  public void addSyntheticDefinition(String sym) {
    occurrences.add(
        Semanticdb.SymbolOccurrence.newBuilder()
            .setSymbol(sym)
            .setRole(Semanticdb.SymbolOccurrence.Role.SYNTHETIC_DEFINITION)
            .build());
  }

  public void addDefinition(String sym) {
    occurrences.add(
        Semanticdb.SymbolOccurrence.newBuilder()
            .setSymbol(sym)
            .setRole(Semanticdb.SymbolOccurrence.Role.DEFINITION)
            .build());
  }
}
