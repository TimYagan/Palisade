package uk.gov.gchq.palisade;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.junit.BeforeClass;
import org.junit.Test;

import org.mockito.Mockito;

import uk.gov.gchq.palisade.data.serialise.Serialiser;
import uk.gov.gchq.palisade.data.serialise.StubSerialiser;
import uk.gov.gchq.palisade.jsonserialisation.JSONSerialiser;
import uk.gov.gchq.palisade.resource.Resource;
import uk.gov.gchq.palisade.resource.StubResource;
import uk.gov.gchq.palisade.service.PalisadeService;
import uk.gov.gchq.palisade.service.request.DataRequestResponse;
import uk.gov.gchq.palisade.service.request.RegisterDataRequest;
import uk.gov.gchq.palisade.service.request.StubConnectionDetail;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PrimitiveIterator;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

public class PalisadeInputFormatTest {

    @Test
    public void shouldSerialiseandDeserialise() throws IOException {
        //Given
        StubSerialiser<Object> serial = new StubSerialiser<>("nothing");
        Configuration c = new Configuration();
        //When
        PalisadeInputFormat.setSerialiser(c, serial);
        Serialiser<Object, Object> deserial = PalisadeInputFormat.getSerialiser(c);
        //Then
        assertEquals(serial, deserial);
    }

    @Test
    public void shouldAddDataRequest() {
        //Given
        Configuration c = new Configuration();
        JobContext mockJob = Mockito.mock(JobContext.class);
        when(mockJob.getConfiguration()).thenReturn(c);

        RegisterDataRequest rdr = new RegisterDataRequest("testResource", new UserId("user"), new Justification("justification"));
        RegisterDataRequest[] rdrArray = {rdr};
        String json = new String(JSONSerialiser.serialise(rdrArray), PalisadeInputFormat.UTF8);
        //When
        PalisadeInputFormat.addDataRequest(mockJob, rdr);
        //Then
        assertEquals(json, c.get(PalisadeInputFormat.REGISTER_REQUESTS_KEY));
    }

    @Test
    public void shouldAddMultipleWithComma() {
        //Given
        Configuration c = new Configuration();
        JobContext mockJob = Mockito.mock(JobContext.class);
        when(mockJob.getConfiguration()).thenReturn(c);
        RegisterDataRequest rdr = new RegisterDataRequest("testResource", new UserId("user"), new Justification("justification"));
        //When
        PalisadeInputFormat.addDataRequest(mockJob, rdr);
        PalisadeInputFormat.addDataRequest(mockJob, rdr);
        RegisterDataRequest[] rdrArray = {rdr, rdr};
        String json = new String(JSONSerialiser.serialise(rdrArray), PalisadeInputFormat.UTF8);
        //Then
        assertEquals(json, c.get(PalisadeInputFormat.REGISTER_REQUESTS_KEY));
    }

    @Test
    public void shouldAddEmptyRequest() {
        //Given
        Configuration c = new Configuration();
        JobContext mockJob = Mockito.mock(JobContext.class);
        when(mockJob.getConfiguration()).thenReturn(c);
        RegisterDataRequest rdr = new RegisterDataRequest();
        RegisterDataRequest[] rdrArray = {rdr};
        String json = new String(JSONSerialiser.serialise(rdrArray), PalisadeInputFormat.UTF8);
        //When
        PalisadeInputFormat.addDataRequest(mockJob, rdr);
        //Then
        assertEquals(json, c.get(PalisadeInputFormat.REGISTER_REQUESTS_KEY));
    }

    @Test
    public void canGetEmptyRequestList() {
        //Given
        Configuration c = new Configuration();
        JobContext mockJob = Mockito.mock(JobContext.class);
        when(mockJob.getConfiguration()).thenReturn(c);
        //When
        //nothing
        //Then
        List<RegisterDataRequest> reqs = PalisadeInputFormat.getDataRequests(mockJob);
        assertEquals(0, reqs.size());
    }

    @Test
    public void addAndGetRequests() {
        //Given
        Configuration c = new Configuration();
        JobContext mockJob = Mockito.mock(JobContext.class);
        when(mockJob.getConfiguration()).thenReturn(c);
        RegisterDataRequest rdr = new RegisterDataRequest("testResource", new UserId("user"), new Justification("justification"));
        RegisterDataRequest rdr2 = new RegisterDataRequest("testResource2", new UserId("user2"), new Justification("justification2"));
        RegisterDataRequest rdr3 = new RegisterDataRequest();
        //When
        PalisadeInputFormat.addDataRequests(mockJob, rdr, rdr2, rdr3);
        List<RegisterDataRequest> expected = Stream.of(rdr, rdr2, rdr3).collect(Collectors.toList());
        //Then
        assertEquals(expected, PalisadeInputFormat.getDataRequests(mockJob));
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowOnNoRequests() throws IOException {
        //Given
        Configuration c = new Configuration();
        JobContext mockJob = Mockito.mock(JobContext.class);
        when(mockJob.getConfiguration()).thenReturn(c);
        PalisadeService server = Mockito.mock(PalisadeService.class);
        PalisadeInputFormat.setPalisadeService(server);
        //When
        //nothing
        //Then
        new PalisadeInputFormat().getSplits(mockJob);
        fail("exception expected");
    }

    @Test
    public void testListToMapCollector() {
        //Given - a Map of numbers to their double
        Map<Integer, Integer> original = IntStream
                .range(1, 20)
                .boxed()
                .collect(Collectors.toMap(Function.identity(), x -> x * 2));
        //When - convert to a list of entry
        List<Map.Entry<Integer, Integer>> entries = original
                .entrySet()
                .stream()
                .collect(Collectors.toList());
        //Then - should equal original map
        assertEquals(original, entries
                        .stream()
                        .collect(PalisadeInputFormat.listToMapCollector())
        );
    }

    @Test
    public void testListToMapCollectorEmpty() {
        //Given - a Map of numbers to their double
        Map<Integer, Integer> original = Collections.emptyMap();
        //When - convert to a list of entry
        List<Map.Entry<Integer, Integer>> entries = original
                .entrySet()
                .stream()
                .collect(Collectors.toList());
        //Then - should equal original map
        assertEquals(original, entries
                        .stream()
                        .collect(PalisadeInputFormat.listToMapCollector())
        );
    }

    @Test
    public void shouldReturnEmptySplits() {
        //Given
        DataRequestResponse req = new DataRequestResponse();
        PrimitiveIterator.OfInt index = IntStream.range(1, 9999).iterator();
        //When
        List<PalisadeInputSplit> result = PalisadeInputFormat.toInputSplits(req, index);
        //Then
        assertEquals(Collections.emptyList(), result);
    }

    private static RegisterDataRequest request1;
    private static DataRequestResponse req1Response;
    private static RegisterDataRequest request2;
    private static DataRequestResponse req2Response;

    @BeforeClass
    public static void setup() {
        request1 = new RegisterDataRequest("res1", new UserId("user1"), new Justification("just1"));
        req1Response = new DataRequestResponse();
        req1Response.getResources().put(new StubResource("type1", "id1", "format1"), new StubConnectionDetail("con1"));
        req1Response.getResources().put(new StubResource("type2", "id2", "format2"), new StubConnectionDetail("con2"));
        req1Response.getResources().put(new StubResource("type3", "id3", "format3"), new StubConnectionDetail("con3"));
        req1Response.getResources().put(new StubResource("type4", "id4", "format4"), new StubConnectionDetail("con4"));
        req1Response.getResources().put(new StubResource("type5", "id5", "format5"), new StubConnectionDetail("con5"));

        request2 = new RegisterDataRequest("res2", new UserId("user2"), new Justification("just2"));
        req2Response = new DataRequestResponse();
        req2Response.getResources().put(new StubResource("type_a", "id6", "format6"), new StubConnectionDetail("con6"));
        req2Response.getResources().put(new StubResource("type_b", "id7", "format7"), new StubConnectionDetail("con7"));
    }

    @Test
    public void shouldReturnSingleSplit() {
        //Given - ask for a single split
        PrimitiveIterator.OfInt index = IntStream.generate(() -> 1).iterator();
        //When
        List<PalisadeInputSplit> result = PalisadeInputFormat.toInputSplits(req1Response, index);
        //Then
        assertEquals(1, result.size());
        assertEquals(5, result.get(0).getRequestResponse().getResources().size());
    }

    @Test
    public void shouldReturnMultipleSplit() {
        //Given - ask for 3 splits
        PrimitiveIterator.OfInt index = IntStream.of(0, 1, 2, 0, 1, 2, 0, 1, 2).iterator();
        //When
        List<PalisadeInputSplit> result = PalisadeInputFormat.toInputSplits(req1Response, index);
        //Then
        assertEquals(3, result.size());
        //should be two in the first two splits, one in the last
        assertEquals(2, result.get(0).getRequestResponse().getResources().size());
        assertEquals(2, result.get(1).getRequestResponse().getResources().size());
        assertEquals(1, result.get(2).getRequestResponse().getResources().size());
        //now check we still got 5 distinct values
        //create set of the values from the map
        Set<String> values0 = result.get(0).getRequestResponse().getResources()
                .values()
                .stream()
                .map(x -> ((StubConnectionDetail) x).getCon())
                .collect(Collectors.toSet());
        Set<String> values1 = result.get(1).getRequestResponse().getResources()
                .values()
                .stream()
                .map(x -> ((StubConnectionDetail) x).getCon())
                .collect(Collectors.toSet());
        Set<String> values2 = result.get(2).getRequestResponse().getResources()
                .values()
                .stream()
                .map(x -> ((StubConnectionDetail) x).getCon())
                .collect(Collectors.toSet());
        Set<String> merged = new HashSet<>();
        merged.addAll(values0);
        merged.addAll(values1);
        merged.addAll(values2);
        assertEquals(5, merged.size());
    }

    /**
     * Simulate a job set up, mock up a job and a palisade service and ask the input format to create splits for it
     *
     * @param maxMapHint maximum mappers to set
     * @param reqs       the map of requests and responses for a palisade service
     * @return input splits
     * @throws IOException shouldn't happen
     */
    public List<InputSplit> callGetSplits(int maxMapHint, Map<RegisterDataRequest, DataRequestResponse> reqs) throws IOException {
        Configuration c = new Configuration();
        JobContext mockJob = Mockito.mock(JobContext.class);
        when(mockJob.getConfiguration()).thenReturn(c);
        //make a mock palisade service that the input format can talk to
        PalisadeService palisadeService = Mockito.mock(PalisadeService.class);
        //tell it what to respond with
        for (Map.Entry<RegisterDataRequest, DataRequestResponse> req : reqs.entrySet()) {
            when(palisadeService.registerDataRequest(req.getKey())).thenReturn(CompletableFuture.supplyAsync(() -> {
                //wait random time for the palisade service to process the resource
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextInt(0, 500));
                } catch (InterruptedException e) {
                }
                return req.getValue();
            }));
        }
        //configure the input format as the client would
        PalisadeInputFormat.setMaxMapTasksHint(mockJob, maxMapHint);
        PalisadeInputFormat.setPalisadeService(palisadeService);
        for (RegisterDataRequest req : reqs.keySet()) {
            PalisadeInputFormat.addDataRequest(mockJob, req);
        }
        //simulate a job run
        PalisadeInputFormat<String> pif = new PalisadeInputFormat<>();
        return pif.getSplits(mockJob);
    }

    @SuppressWarnings("unchecked")
    private static <R extends InputSplit> List<R> convert(List<InputSplit> list) {
        return (List<R>) list;
    }

    @Test
    public void shouldCreateOneSplitFromOneRequest() throws IOException {
        //Given
        Map<RegisterDataRequest, DataRequestResponse> resources = new HashMap<>();
        resources.put(request1, req1Response);
        //When
        List<PalisadeInputSplit> splits = convert(callGetSplits(1, resources));
        //Then
        checkForExpectedResources(splits, 1, 5);
    }

    @Test
    public void shouldCreateTwoSplitFromOneRequest() throws IOException {
        //Given
        Map<RegisterDataRequest, DataRequestResponse> resources = new HashMap<>();
        resources.put(request1, req1Response);
        //When
        List<PalisadeInputSplit> splits = convert(callGetSplits(2, resources));
        //Then
        checkForExpectedResources(splits, 2, 5);
    }

    @Test
    public void shouldCreateManySplitFromOneRequest() throws IOException {
        //Given
        Map<RegisterDataRequest, DataRequestResponse> resources = new HashMap<>();
        resources.put(request1, req1Response);
        //When
        List<PalisadeInputSplit> splits = convert(callGetSplits(99999, resources));
        //Then
        checkForExpectedResources(splits, 5, 5);
    }

    @Test
    public void shouldCreateTwoSplitsFromTwoRequests() throws IOException {
        //Given
        Map<RegisterDataRequest, DataRequestResponse> resources = new HashMap<>();
        resources.put(request1, req1Response);
        resources.put(request2, req2Response);
        //When
        List<PalisadeInputSplit> splits = convert(callGetSplits(1, resources));
        //Then
        checkForExpectedResources(splits, 2, 7);
    }

    @Test
    public void shouldCreateFourSplitsFromTwoRequests() throws IOException {
        //Given
        Map<RegisterDataRequest, DataRequestResponse> resources = new HashMap<>();
        resources.put(request1, req1Response);
        resources.put(request2, req2Response);
        //When
        List<PalisadeInputSplit> splits = convert(callGetSplits(2, resources));
        //Then
        checkForExpectedResources(splits, 4, 7);
    }

    @Test
    public void shouldCreateManySplitsFromTwoRequests() throws IOException {
        //Given
        Map<RegisterDataRequest, DataRequestResponse> resources = new HashMap<>();
        resources.put(request1, req1Response);
        resources.put(request2, req2Response);
        //When
        List<PalisadeInputSplit> splits = convert(callGetSplits(7, resources));
        //Then
        checkForExpectedResources(splits, 7, 7);
    }

    @Test
    public void shouldCreateManySplitsFromTwoRequestsNoMapHint() throws IOException {
        //Given
        Map<RegisterDataRequest, DataRequestResponse> resources = new HashMap<>();
        resources.put(request1, req1Response);
        resources.put(request2, req2Response);
        //When
        List<PalisadeInputSplit> splits = convert(callGetSplits(0, resources));
        //Then
        checkForExpectedResources(splits, 7, 7);
    }

    private void checkForExpectedResources(List<PalisadeInputSplit> splits, int expectedSplits, int expectedNumberResources) {
        assertEquals(expectedSplits, splits.size());
        //combine all the resources from both splits and check we have all 5 resources covered
        //first check expectedTotal total
        assertEquals(expectedNumberResources, splits
                .stream()
                .flatMap(split -> split.getRequestResponse().getResources().entrySet().stream())
                .count());
        //check for no duplicates
        Set<Resource> allResponses = splits
                .stream()
                .flatMap(split -> split.getRequestResponse().getResources().keySet().stream())
                .collect(Collectors.toSet());
        assertEquals(expectedNumberResources, allResponses.size());
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowOnNegativeMapHint() throws IOException {
        //Given
        Configuration c = new Configuration();
        JobContext mockJob = Mockito.mock(JobContext.class);
        when(mockJob.getConfiguration()).thenReturn(c);
        //make a mock palisade service that the input format can talk to
        PalisadeService palisadeService = Mockito.mock(PalisadeService.class);
        //When
        PalisadeInputFormat.addDataRequest(mockJob, request1);
        c.setInt(PalisadeInputFormat.MAXIMUM_MAP_HINT_KEY, -1);
        //directly put illegal value into config
        //Then
        new PalisadeInputFormat<String>().getSplits(mockJob);
        fail("Should throw exception");
    }

    @Test(expected = NullPointerException.class)
    public void shouldThrowOnNoPalisadeService() throws IOException {
        //Given
        JobContext mockJob = Mockito.mock(JobContext.class);
        //When - nothing
        //Then
        new PalisadeInputFormat<String>().getSplits(mockJob);
        fail("Should throw exception");
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowWhenMaxMapHintNegative() {
        //Given
        Configuration c = new Configuration();
        JobContext mockJob = Mockito.mock(JobContext.class);
        when(mockJob.getConfiguration()).thenReturn(c);
        //When
        PalisadeInputFormat.setMaxMapTasksHint(mockJob, -1);
        fail("Should throw exception");
    }
}