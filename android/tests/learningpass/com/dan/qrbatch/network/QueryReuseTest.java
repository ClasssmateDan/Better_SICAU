package com.dan.qrbatch.network;
import java.net.URL;
import org.junit.Test;
import static org.junit.Assert.*;
public class QueryReuseTest {
    @Test public void onlyReadOnlyDiscoveryMayReuseConnections()throws Exception{
        assertTrue(ChaoxingClient.reusableQuery("POST",new URL("https://mooc1-1.chaoxing.com/visit/courselistdata")));
        assertTrue(ChaoxingClient.reusableQuery("GET",new URL("https://mobilelearn.chaoxing.com/v2/apis/active/student/activelist?courseId=1")));
        for(String path:new String[]{"/newsign/preSign","/pptSign/analysis","/pptSign/analysis2","/pptSign/stuSignajax"})
            assertFalse(ChaoxingClient.reusableQuery("GET",new URL("https://mobilelearn.chaoxing.com"+path)));
        assertFalse(ChaoxingClient.reusableQuery("POST",new URL("https://passport2.chaoxing.com/fanyalogin")));
        assertFalse(ChaoxingClient.reusableQuery("POST",new URL("https://mobilelearn.chaoxing.com/v2/apis/active/student/activelist")));
    }
}
