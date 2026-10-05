package atlas.poc;
import java.util.*;
import org.junit.jupiter.api.Test;
import static atlas.poc.Transactions.*;
import static org.junit.jupiter.api.Assertions.*;
/** Fixed spec vectors avoid a hash/codec implementation validating itself. */
class CanonicalVectorsTest {
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Give downstream teams reproducible intent identities
     * Boundary: Compare the bounded offer schema against fixed expected bytes and hashes; reject a corrupt size summary
     * Expected: The canonical representation matches the published vectors and rejects inconsistent summaries.
     */
    @org.junit.jupiter.api.DisplayName("AT-006 | Give downstream teams reproducible intent identities")
    // END ATLAS SCENARIO
    @Test void matchesFixedCanonicalProfileVectorsAndByteSummary() {
        var cells=new EnumMap<Group,Cell>(Group.class);
        cells.put(Group.ECONOMICS,new Cell(new UUID(0,1),"500"));
        cells.put(Group.ELIGIBILITY,new Cell(new UUID(0,2),"NEW"));
        cells.put(Group.ROYALTY,new Cell(new UUID(0,3),"1500"));
        Snapshot snapshot=new Snapshot(new UUID(0,4),1,128,43,cells);
        assertEquals("b83a7591e856ef6b8288ecb7f698051ec541200fcb6db2d1c7d40342d1fa82ea",snapshot.intentId());
        assertEquals(43,Transactions.bytes(cells));
        Request request=new Request(new UUID(0,5),1,Map.of(Group.ROYALTY,new UUID(0,3)),Map.of(Group.ROYALTY,"2000"),null,null);
        assertEquals("565d51fca8ce04bc74bb45988b407f5aa5ce9fd0625ed4e3e40defce1ed50cad",request.hash());
        assertEquals(snapshot,TransactionCodec.decode(TransactionCodec.encode(snapshot)));
        String badSummary=TransactionCodec.encode(snapshot).replace("|128|43|","|128|42|");
        assertThrows(IllegalArgumentException.class,()->TransactionCodec.decode(badSummary));
    }
}
