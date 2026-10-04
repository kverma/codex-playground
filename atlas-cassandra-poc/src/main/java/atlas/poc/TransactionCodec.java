package atlas.poc;

import java.util.*;
import static atlas.poc.Transactions.*;

/** Versioned bounded wire representation; values containing delimiters are rejected by admission. */
final class TransactionCodec {
    static String encode(Snapshot snapshot) {
        var out=new StringBuilder("1|"+snapshot.generation()+"|"+snapshot.epoch()+"|"+snapshot.budget()+"|"+snapshot.payloadBytes());
        for(Group group:Group.values()) out.append('|').append(snapshot.cells().get(group).version()).append('|').append(snapshot.value(group));
        return out.toString();
    }
    static Snapshot decode(String text) {
        String[] parts=text.split("\\|",-1);
        if(parts.length!=11||!parts[0].equals("1")) throw new IllegalArgumentException("snapshot codec version/shape");
        var cells=new EnumMap<Group,Cell>(Group.class);
        for(Group group:Group.values()) cells.put(group,new Cell(UUID.fromString(parts[5+2*group.ordinal()]),parts[6+2*group.ordinal()]));
        Snapshot result=new Snapshot(UUID.fromString(parts[1]),Long.parseLong(parts[2]),Integer.parseInt(parts[3]),Integer.parseInt(parts[4]),cells);
        if(result.payloadBytes()!=bytes(cells)) throw new IllegalArgumentException("payload summary mismatch");
        return result;
    }
}
