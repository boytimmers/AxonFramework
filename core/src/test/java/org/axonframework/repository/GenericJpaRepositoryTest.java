/*
 * Copyright (c) 2010-2012. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.repository;

import org.axonframework.common.jpa.SimpleEntityManagerProvider;
import org.axonframework.domain.AbstractAggregateRoot;
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork;
import org.axonframework.messaging.unitofwork.DefaultUnitOfWork;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.persistence.EntityManager;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * @author Allard Buijze
 */
public class GenericJpaRepositoryTest {

    private EntityManager mockEntityManager;
    private GenericJpaRepository<StubJpaAggregate> testSubject;
    private String aggregateId;
    private StubJpaAggregate aggregate;

    @Before
    public void setUp() {
        mockEntityManager = mock(EntityManager.class);
        testSubject = new GenericJpaRepository<>(new SimpleEntityManagerProvider(mockEntityManager),
                                                                 StubJpaAggregate.class);
        DefaultUnitOfWork.startAndGet(null);
        aggregateId = "123";
        aggregate = new StubJpaAggregate(aggregateId);
        when(mockEntityManager.find(StubJpaAggregate.class, "123")).thenReturn(aggregate);
    }

    @After
    public void cleanUp() {
        while (CurrentUnitOfWork.isStarted()) {
            CurrentUnitOfWork.get().rollback();
        }
    }

    @Test
    public void testLoadAggregate() {
        StubJpaAggregate actualResult = testSubject.load(aggregateId);
        assertSame(aggregate, actualResult);
    }

    @Test
    public void testLoadAggregate_NotFound() {
        String aggregateIdentifier = UUID.randomUUID().toString();
        try {
            testSubject.load(aggregateIdentifier);
            fail("Expected AggregateNotFoundException");
        } catch (AggregateNotFoundException e) {
            assertEquals(aggregateIdentifier, e.getAggregateIdentifier());
        }
    }

    @Test
    public void testLoadAggregate_WrongVersion() {
        try {
            testSubject.load(aggregateId, 2L);
            fail("Expected ConflictingAggregateVersionException");
        } catch (ConflictingAggregateVersionException e) {
            assertEquals(2L, e.getExpectedVersion());
            assertEquals(0L, e.getActualVersion());
        }
    }

    @Test
    public void testPersistAggregate_DefaultFlushMode() {
        testSubject.doSave(aggregate);
        verify(mockEntityManager).persist(aggregate);
        verify(mockEntityManager).flush();
    }

    @Test
    public void testPersistAggregate_ExplicitFlushModeOn() {
        testSubject.setForceFlushOnSave(true);
        testSubject.doSave(aggregate);
        verify(mockEntityManager).persist(aggregate);
        verify(mockEntityManager).flush();
    }

    @Test
    public void testPersistAggregate_ExplicitFlushModeOff() {
        testSubject.setForceFlushOnSave(false);
        testSubject.doSave(aggregate);
        verify(mockEntityManager).persist(aggregate);
        verify(mockEntityManager, never()).flush();
    }
    
    private class StubJpaAggregate extends AbstractAggregateRoot {

        private final String identifier;

        private StubJpaAggregate(String identifier) {
            this.identifier = identifier;
        }

        @Override
        public String getIdentifier() {
            return identifier;
        }

        @Override
        public Long getVersion() {
            return 0L;
        }
    }
}
