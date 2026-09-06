package com.dsoft.pb.idef.report;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.ramussoft.common.Attribute;
import com.ramussoft.common.AttributeType;
import com.ramussoft.common.Element;
import com.ramussoft.common.Engine;
import com.ramussoft.common.Qualifier;
import com.ramussoft.core.attribute.simple.HierarchicalPersistent;
import com.ramussoft.core.attribute.simple.HierarchicalPlugin;
import com.ramussoft.idef0.IDEF0Plugin;
import com.ramussoft.report.data.Data;
import com.ramussoft.report.data.Row;
import com.ramussoft.report.data.Rows;

/**
 * IDEF0Buffer is the store behind the Inputs/Outputs/Controls/Mechanisms report
 * keywords: FastIdef0Connection fills it from the sector table and then answers every
 * report query out of it. Its whole contract is "a link put in can be got back out".
 *
 * Everything below is driven through a java.lang.reflect.Proxy Engine: no database, no
 * GUI, no .rsf model file. The engine only has to answer six methods; the RowSets, the
 * hierarchy and the element-load filter are the production classes.
 */
public class IDEF0BufferTest {

    private static final long MODEL_Q = 100L;
    private static final long DOC_Q = 200L;
    private static final long STREAM_Q = 300L;

    private static final long DOC = 1L;
    private static final long FUNCTION = 2L;
    private static final long EXTERNAL_REFERENCE = 3L;
    private static final long FUNCTION_2 = 4L;

    private Attribute h;
    private Attribute name;
    private Attribute type;

    private Qualifier modelQ;
    private Qualifier docQ;
    private Qualifier streamQ;

    private final Map<Long, Element> elements = new HashMap<Long, Element>();
    private final Map<Long, Integer> types = new HashMap<Long, Integer>();
    private final Map<Long, HierarchicalPersistent> hierarchy = new HashMap<Long, HierarchicalPersistent>();

    private Data data;

    @Before
    public void setUp() throws Exception {
        h = attribute(10L, HierarchicalPlugin.HIERARHICAL_ATTRIBUTE, new AttributeType(
                "Core", "Hierarchical", true));
        name = attribute(11L, "Name", new AttributeType("Core", "Text", true));
        type = attribute(12L, IDEF0Plugin.F_TYPE, new AttributeType("IDEF0", "Type", false));

        modelQ = qualifier(MODEL_Q, "Model", Arrays.asList(name, type), 11L);
        // installFunctionAttributes() puts F_TYPE among the *system* attributes of every
        // function qualifier; that is what switches IDEF0FunctionFilter on for the model.
        modelQ.setSystemAttributes(new ArrayList<Attribute>(Arrays.asList(type)));
        docQ = qualifier(DOC_Q, "Documents", Arrays.asList(name), 11L);
        streamQ = qualifier(STREAM_Q, "Streams", Arrays.asList(name), 11L);

        element(DOC, DOC_Q, "Order form", -1L, null);
        element(FUNCTION, MODEL_Q, "Accept order", -1L, Integer.valueOf(1));      // TYPE_PROCESS
        element(EXTERNAL_REFERENCE, MODEL_Q, "Ref", FUNCTION, Integer.valueOf(1001)); // TYPE_EXTERNAL_REFERENCE
        element(FUNCTION_2, MODEL_Q, "Ship order", -1L, Integer.valueOf(1));

        data = new Data(engine());
    }

    /**
     * DEFECT A. addRowFunction keys the map by a copy carrying the link's element status;
     * a report iterating the Documents qualifier looks up with a status-free row, and
     * report.data.Row folds elementStatus into equals and hashCode - so the two hashed to
     * different buckets and the query returned nothing, with no error. Both sides are now
     * normalised. Revert that and this returns 0.
     */
    @Test
    public void linkCarryingAnElementStatusIsStillFoundByAStatusFreeRow() {
        IDEF0Buffer buffer = new IDEF0Buffer(data, modelQ);
        buffer.addRowFunction(DOC, FUNCTION, "8883|", null); // what SectorRowsEditor stores
        buffer.commit(true);

        assertEquals(1, buffer.getFunctions(data.findRow(DOC)).size());
    }

    /**
     * Control. The identical link without a status is found, and was found before either
     * fix - without it a test that simply never worked would look like a caught defect.
     */
    @Test
    public void linkWithoutAnElementStatusIsFound() {
        IDEF0Buffer buffer = new IDEF0Buffer(data, modelQ);
        buffer.addRowFunction(DOC, FUNCTION, null, null);
        buffer.commit(true);

        assertEquals(1, buffer.getFunctions(data.findRow(DOC)).size());
    }

    /**
     * DEFECT B. getChildCount counts a child while type <= 1001, so a leaf function whose
     * decomposition holds only an external reference (1001) is taken for decomposed and
     * commit(false) drops it. commit(false) is what every non-"All" keyword uses. The bound
     * is now < 1001, the one IDEF0FunctionFilter uses; put the old one back and this
     * returns 0.
     */
    @Test
    public void leafFunctionWithAnExternalReferenceSurvivesANonAllConnection() {
        IDEF0Buffer buffer = new IDEF0Buffer(data, modelQ);
        buffer.addRowFunction(DOC, FUNCTION, null, null);
        buffer.commit(false);

        assertEquals(1, buffer.getFunctions(data.findRow(DOC)).size());
    }

    // ---------------------------------------------------------------- fixtures

    private static Attribute attribute(long id, String n, AttributeType t) {
        Attribute a = new Attribute();
        a.setId(id);
        a.setName(n);
        a.setAttributeType(t);
        return a;
    }

    private static Qualifier qualifier(long id, String n, List<Attribute> attrs, long forName) {
        Qualifier q = new Qualifier();
        q.setId(id);
        q.setName(n);
        q.setAttributes(new ArrayList<Attribute>(attrs));
        q.setAttributeForName(forName);
        return q;
    }

    private void element(long id, long qualifierId, String n, long parent, Integer t) {
        elements.put(id, new Element(id, qualifierId, n));
        HierarchicalPersistent p = new HierarchicalPersistent();
        p.setParentElementId(parent);
        p.setPreviousElementId(-1L);
        hierarchy.put(id, p);
        if (t != null)
            types.put(id, t);
    }

    @SuppressWarnings("unchecked")
    private Engine engine() throws Exception {
        // IDEF0Plugin.getFunctionTypeAttribute() reads a private list on the plugin
        // instance the engine hands out; init() needs a real database, so inject instead.
        final IDEF0Plugin plugin = new IDEF0Plugin();
        Field f = IDEF0Plugin.class.getDeclaredField("functionAttributes");
        f.setAccessible(true);
        ((List<Attribute>) f.get(plugin)).add(type);

        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method m, Object[] args) {
                String n = m.getName();
                if ("getPluginProperty".equals(n)) {
                    String p = (String) args[0];
                    String k = (String) args[1];
                    if (HierarchicalPlugin.HIERARHICAL_ATTRIBUTE.equals(k))
                        return h;
                    if ("IDEF0".equals(p) && IDEF0Plugin.F_STREAMS.equals(k))
                        return streamQ;
                    if ("IDEF0".equals(p) && "IDEF0_PLUGIN".equals(k))
                        return plugin; // IDEF0Plugin.PLUGIN, which is private
                    return null;
                }
                if ("getElements".equals(n) && args.length == 2 && args[0] instanceof Qualifier) {
                    Qualifier q = (Qualifier) args[0];
                    List<Attribute> attrs = (List<Attribute>) args[1];
                    Hashtable<Element, Object[]> res = new Hashtable<Element, Object[]>();
                    for (Element e : elements.values()) {
                        if (e.getQualifierId() != q.getId())
                            continue;
                        Object[] objects = new Object[attrs.size()];
                        for (int i = 0; i < attrs.size(); i++) {
                            if (attrs.get(i).getId() == h.getId())
                                objects[i] = hierarchy.get(e.getId());
                            else if (attrs.get(i).getId() == name.getId())
                                objects[i] = e.getName();
                        }
                        res.put(e, objects);
                    }
                    return res;
                }
                if ("getAttribute".equals(n) && args.length == 2 && args[0] instanceof Element) {
                    Element e = (Element) args[0];
                    Attribute a = (Attribute) args[1];
                    if (e != null && a != null && a.getId() == type.getId())
                        return types.get(e.getId());
                    return null;
                }
                if ("getQualifierIdForElement".equals(n)) {
                    Element e = elements.get(args[0]);
                    return Long.valueOf(e == null ? -1L : e.getQualifierId());
                }
                if ("getQualifier".equals(n) && args.length == 1 && args[0] instanceof Long) {
                    long id = ((Long) args[0]).longValue();
                    if (id == MODEL_Q)
                        return modelQ;
                    if (id == DOC_Q)
                        return docQ;
                    if (id == STREAM_Q)
                        return streamQ;
                    return null;
                }
                if ("getActiveBranch".equals(n))
                    return Long.valueOf(0L);
                Class<?> rt = m.getReturnType();
                if (!rt.isPrimitive())
                    return null;
                if (rt == boolean.class)
                    return Boolean.FALSE;
                if (rt == long.class)
                    return Long.valueOf(0L);
                if (rt == int.class)
                    return Integer.valueOf(0);
                return null;
            }
        };
        return (Engine) Proxy.newProxyInstance(Engine.class.getClassLoader(),
                new Class[]{Engine.class}, handler);
    }
}
