package atlas.poc;
import java.util.*;
import org.junit.jupiter.api.Test;
import static atlas.poc.Transactions.*;
import static atlas.poc.TraceAssertions.*;
/** Fixed spec vectors avoid a hash/codec implementation validating itself. */
class CanonicalVectorsTest {
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Keep bounded intent and request encodings reproducible across languages
     * Boundary: Generate 256 independent Python byte/hash vectors and compare Java results with reversed map order and changed storage tokens
     * Expected: Complete codec bytes and hashes agree, storage metadata does not alter semantic IDs and a changed royalty does.
     */
    @org.junit.jupiter.api.DisplayName("AT-100 | Keep bounded intent and request encodings reproducible across languages")
    // END ATLAS SCENARIO
    @Test void independentPythonEncoderMatchesBoundedProfileAndMetadataInvariance() throws Exception {
        var dir=java.nio.file.Path.of("build/evidence/canonical-reference");java.nio.file.Files.createDirectories(dir);
        var file=dir.resolve("vectors.json");
        var process=new ProcessBuilder("python3","scripts/canonical-reference.py").redirectOutput(file.toFile()).redirectError(dir.resolve("python.log").toFile()).start();
        try { assertTrue(process.waitFor(20,java.util.concurrent.TimeUnit.SECONDS)); }
        finally { if(process.isAlive())process.destroyForcibly(); }
        assertEquals(0,process.exitValue());
        var vectors=new com.fasterxml.jackson.databind.ObjectMapper().readTree(file.toFile());assertEquals(256,vectors.size());
        for(var vector:vectors) {
            var cells=new LinkedHashMap<Group,Cell>();
            // Deliberately reverse insertion order relative to the independent encoder.
            for(int i=2;i>=0;i--)cells.put(Group.values()[i],new Cell(UUID.fromString(vector.get("versions").get(i).asText()),vector.get("values").get(i).asText()));
            var snapshot=new Snapshot(UUID.fromString(vector.get("generation").asText()),vector.get("epoch").asLong(),128,vector.get("size").asInt(),cells);
            assertEquals(vector.get("intentHash").asText(),snapshot.intentId());assertEquals(vector.get("wire").asText(),TransactionCodec.encode(snapshot));
            assertEquals(snapshot,TransactionCodec.decode(vector.get("wire").asText()));assertEquals(vector.get("size").asInt(),Transactions.bytes(cells));
            var metadata=new EnumMap<Group,Cell>(Group.class);cells.forEach((g,c)->metadata.put(g,new Cell(UUID.randomUUID(),c.value())));
            assertEquals(snapshot.intentId(),new Snapshot(UUID.randomUUID(),snapshot.epoch(),256,snapshot.payloadBytes(),metadata).intentId());
            var changed=new EnumMap<Group,Cell>(cells);changed.put(Group.ROYALTY,new Cell(UUID.randomUUID(),Integer.toString((Integer.parseInt(snapshot.value(Group.ROYALTY))+1)%10001)));
            assertNotEquals(snapshot.intentId(),new Snapshot(UUID.randomUUID(),snapshot.epoch(),128,Transactions.bytes(changed),changed).intentId());
            var reads=new LinkedHashMap<Group,UUID>();var updates=new LinkedHashMap<Group,String>();
            for(int i=2;i>=0;i--) { Group g=Group.values()[i];if(vector.get("reads").has(g.name()))reads.put(g,UUID.fromString(vector.get("reads").get(g.name()).asText()));if(vector.get("updates").has(g.name()))updates.put(g,vector.get("updates").get(g.name()).asText()); }
            var request=new Request(UUID.randomUUID(),snapshot.epoch(),reads,updates,vector.get("admittedEpoch").isNull()?null:vector.get("admittedEpoch").asLong(),vector.get("admittedBudget").isNull()?null:vector.get("admittedBudget").asInt());
            assertEquals(vector.get("requestHash").asText(),request.hash());
            assertEquals(request.hash(),new Request(UUID.randomUUID(),request.epoch(),reads,updates,request.admittedEpoch(),request.admittedBudget()).hash());
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Reject ambiguous commercial strings before committing state
     * Boundary: Submit noncanonical numbers, delimiters, Unicode digits and malformed eligibility lists
     * Expected: Every invalid request rejects explicitly without changing the complete state.
     */
    @org.junit.jupiter.api.DisplayName("AT-101 | Reject ambiguous commercial strings before committing state")
    // END ATLAS SCENARIO
    @Test void malformedCommercialValuesCannotEnterCanonicalState() {
        var model=new Model();var before=model.read();
        var bad=List.of("-1","1000001","0500","+500"," 500","500 ","5e2","500.0","500|NEW","", "５００");
        for(String value:bad) {
            var request=Transactions.edit(UUID.randomUUID(),before,Map.of(Group.ECONOMICS,value));
            var error=assertThrows(Rejected.class,()->model.commit(request));assertEquals(Transactions.Error.INVALID,error.error);assertEquals(before,model.read());
        }
        for(String value:List.of("NEW|CHURNED","CHURNED,NEW","new","NEW,CHURNED,NEW","NEW;CHURNED")) {
            var error=assertThrows(Rejected.class,()->model.commit(Transactions.edit(UUID.randomUUID(),before,Map.of(Group.ELIGIBILITY,value))));assertEquals(Transactions.Error.INVALID,error.error);assertEquals(before,model.read());
        }
    }
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
