package com.github.tlb.factory;

import com.github.tlb.service.TalkToCruise;
import com.github.tlb.service.TalkToService;
import com.github.tlb.service.http.DefaultHttpAction;
import com.github.tlb.splitter.TalksToService;
import com.github.tlb.utils.SystemEnvironment;
import com.github.tlb.splitter.TestSplitterCriteria;
import com.github.tlb.splitter.JobFamilyAwareSplitterCriteria;
import com.github.tlb.orderer.TestOrderer;

import java.lang.reflect.InvocationTargetException;

/**
 * @understands creating a criteria based on the class
 */
public class TlbFactory<T> {
    public static final String COUNT = "com.github.tlb.splitter.CountBasedTestSplitterCriteria";
    public static final String TIME = "com.github.tlb.splitter.TimeBasedTestSplitterCriteria";
    public static final String FAILED_FIRST = "com.github.tlb.orderer.FailedFirstOrderer";
    private Class<T> klass;
    private T defaultValue;
    private static TlbFactory<TestSplitterCriteria> criteriaFactory;
    private static TlbFactory<TestOrderer> testOrderer;

    TlbFactory(Class<T> klass, T defaultValue) {
        this.klass = klass;
        this.defaultValue = defaultValue;
    }

    public <T> T getInstance(String klassName, SystemEnvironment environment) {
        if (klassName == null || klassName.isEmpty()) {
            return (T) defaultValue;
        }
        try {
            Class<?> criteriaClass = Class.forName(klassName);
            if(!klass.isAssignableFrom(criteriaClass)) {
                throw new IllegalArgumentException("Class '" + klassName + "' does not implement TestSplitterCriteria");
            }
            return getInstance((Class<? extends T>) criteriaClass, environment);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Unable to locate Criteria class '" + klassName + "'");
        }
    }

    <T> T getInstance(Class<? extends T> actualKlass, SystemEnvironment environment) {
        try {
            T criteria = actualKlass.getConstructor(SystemEnvironment.class).newInstance(environment);
            if (TalksToService.class.isInstance(criteria)) {
                TalkToService service = new TalkToCruise(environment, new DefaultHttpAction(environment));
                ((TalksToService)criteria).talksToService(service);
            }
            return criteria;
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Public constructor matching " + actualKlass.getName() + "(SystemEnvironment) was not found");
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException("Public constructor matching " + actualKlass.getName() + "(SystemEnvironment) was not found");
        } catch (InstantiationException e) {
            throw new IllegalArgumentException("Unable to create abstract class " + actualKlass.getName());
        }
    }

    public static TestSplitterCriteria getCriteria(String criteriaName, SystemEnvironment environment) {
        if (criteriaFactory == null)
            criteriaFactory = new TlbFactory<TestSplitterCriteria>(TestSplitterCriteria.class, JobFamilyAwareSplitterCriteria.MATCH_ALL_FILE_SET);
        return criteriaFactory.getInstance(criteriaName, environment);
    }

    public static TestOrderer getOrderer(String ordererName, SystemEnvironment environment) {
        if (testOrderer == null)
            testOrderer = new TlbFactory<TestOrderer>(TestOrderer.class, TestOrderer.NO_OP);
        return testOrderer.getInstance(ordererName, environment);
    }
}