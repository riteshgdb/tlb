package com.github.tlb.server;

import com.github.tlb.domain.SubsetSizeEntry;
import com.github.tlb.server.resources.ProcessSubsetSize;
import org.junit.Before;
import org.junit.Test;
import org.restlet.*;
import org.restlet.util.RouteList;

import java.util.HashMap;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.junit.matchers.JUnitMatchers.hasItem;
import static org.mockito.Mockito.mock;

public class TlbApplicationTest {
    private TlbApplication app;

    @Before
    public void setUp() {
        Context context = mock(Context.class);
        app = new TlbApplication(context);
    }

    @Test
    public void shouldGenerateRouteForSubsetSize() {
        HashMap<String, Restlet> routeMaping = getRoutePatternsAndResources();
        assertThat(routeMaping.keySet(), hasItem("/{family}/subset_size"));
        Restlet restlet = routeMaping.get("/{family}/subset_size");
        assertThat(((Finder)restlet).getTargetClass().getName(), is(ProcessSubsetSize.class.getName()));
    }

    private HashMap<String, Restlet> getRoutePatternsAndResources() {
        Router router = (Router) app.createRoot();
        RouteList routeList = router.getRoutes();
        HashMap<String, Restlet> map = new HashMap<String, Restlet>();
        for (Route route : routeList) {
            map.put(route.getTemplate().getPattern(), route.getNext());
        }
        return map;
    }


}