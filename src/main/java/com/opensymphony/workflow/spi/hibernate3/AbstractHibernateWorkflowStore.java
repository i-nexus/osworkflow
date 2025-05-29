/*
 * Copyright (c) 2002-2003 by OpenSymphony
 * All rights reserved.
 */
package com.opensymphony.workflow.spi.hibernate3;

import com.opensymphony.module.propertyset.PropertySet;
import com.opensymphony.workflow.QueryNotSupportedException;
import com.opensymphony.workflow.StoreException;
import com.opensymphony.workflow.query.FieldExpression;
import com.opensymphony.workflow.query.NestedExpression;
import com.opensymphony.workflow.query.WorkflowExpressionQuery;
import com.opensymphony.workflow.query.WorkflowQuery;
import com.opensymphony.workflow.spi.Step;
import com.opensymphony.workflow.spi.WorkflowEntry;
import com.opensymphony.workflow.spi.WorkflowStore;
import com.opensymphony.workflow.spi.hibernate.HibernateCurrentStep;
import com.opensymphony.workflow.spi.hibernate.HibernateHistoryStep;
import com.opensymphony.workflow.spi.hibernate.HibernateStep;
import com.opensymphony.workflow.spi.hibernate.HibernateWorkflowEntry;
import com.opensymphony.workflow.util.PropertySetDelegate;
import jakarta.persistence.criteria.*;
import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.hibernate.query.Query;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;

import java.util.*;

import static com.opensymphony.workflow.query.WorkflowQuery.*;


/**
 * @author Luca Masini
 * @since 2005-9-23
 *
 */
public abstract class AbstractHibernateWorkflowStore implements WorkflowStore {
    //~ Instance fields ////////////////////////////////////////////////////////

    private PropertySetDelegate propertySetDelegate;
    private String cacheRegion = null;
    private boolean cacheable = false;

    //~ Methods ////////////////////////////////////////////////////////////////

    // ~ Getter/Setter ////////////////////////////////////////////////////////////////
    public void setCacheRegion(String cacheRegion) {
        this.cacheRegion = cacheRegion;
    }

    public void setCacheable(boolean cacheable) {
        this.cacheable = cacheable;
    }

    public void setEntryState(final long entryId, final int state) throws StoreException {
        loadEntry(entryId).setState(state);
    }

    public PropertySet getPropertySet(long entryId) throws StoreException {
        if (getPropertySetDelegate() == null) {
            throw new StoreException("PropertySetDelegate is not properly configured");
        }

        return getPropertySetDelegate().getPropertySet(entryId);
    }

    public void setPropertySetDelegate(PropertySetDelegate propertySetDelegate) {
        this.propertySetDelegate = propertySetDelegate;
    }

    public PropertySetDelegate getPropertySetDelegate() {
        return propertySetDelegate;
    }

    public Step createCurrentStep(final long entryId, final int stepId, final String owner, final Date startDate, final Date dueDate, final String status, final long[] previousIds) throws StoreException {
        final HibernateWorkflowEntry entry = loadEntry(entryId);
        final HibernateCurrentStep step = new HibernateCurrentStep();

        step.setStepId(stepId);
        step.setOwner(owner);
        step.setStartDate(startDate);
        step.setDueDate(dueDate);
        step.setStatus(status);

        // This is for backward compatibility, but current Store doesn't
        // persist this collection, nor is such property visibile outside
        // OSWF internal classes
        List<HibernateCurrentStep> previousSteps = new ArrayList<>(previousIds.length);

        for (int i = 0; i < previousIds.length; i++) {
            HibernateCurrentStep previousStep = new HibernateCurrentStep();
            previousSteps.add(previousStep);
        }

        step.setPreviousSteps(previousSteps);

        entry.addCurrentSteps(step);

        // We need to save here because we soon will need the stepId
        // that hibernate calculate on save or flush
        save(step);

        return step;
    }

    public WorkflowEntry createEntry(String workflowName) throws StoreException {
        final HibernateWorkflowEntry entry = new HibernateWorkflowEntry();
        entry.setState(WorkflowEntry.CREATED);
        entry.setWorkflowName(workflowName);
        save(entry);

        return entry;
    }

    public List<HibernateCurrentStep> findCurrentSteps(final long entryId) throws StoreException {
        // We are asking for current step list, so here we have an anti-lazy
        // copy of the Hibernate array in memory. This also prevents problem
        // in case the use is going with a pattern that span a session
        // for method call
        return new ArrayList<>(loadEntry(entryId).getCurrentSteps());
    }

    public WorkflowEntry findEntry(long entryId) throws StoreException {
        return loadEntry(entryId);
    }

    public List<HibernateHistoryStep> findHistorySteps(final long entryId) throws StoreException {
        // We are asking for current step list, so here we have an anti-lazy
        // copy of the Hibernate array in memory. This also prevents problem
        // in case the use is going with a pattern that span a session
        // for method call
        return new ArrayList<>(loadEntry(entryId).getHistorySteps());
    }

    public Step markFinished(Step step, int actionId, Date finishDate, String status, String caller) throws StoreException {
        final HibernateCurrentStep currentStep = (HibernateCurrentStep) step;

        currentStep.setActionId(actionId);
        currentStep.setFinishDate(finishDate);
        currentStep.setStatus(status);
        currentStep.setCaller(caller);

        return currentStep;
    }

    public void moveToHistory(final Step step) throws StoreException {
        final HibernateCurrentStep currentStep = (HibernateCurrentStep) step;
        final HibernateWorkflowEntry entry = currentStep.getEntry();
        final HibernateHistoryStep hStep = new HibernateHistoryStep(currentStep);

        entry.removeCurrentSteps(currentStep);
        delete(currentStep);
        entry.addHistorySteps(hStep);

        // We need to save here because we soon will need the stepId
        // that hibernate calculate on save or flush
        save(hStep);
    }

    public List<Long> query(final WorkflowQuery query) throws StoreException {
        return execute(session -> {
            int qtype = query.getType();

            if (qtype == 0) { // then not set, so look in sub queries
                if (query.getLeft() != null) {
                    qtype = query.getLeft().getType();
                }
            }

            Class<? extends HibernateStep> entityClass;
            if (qtype == CURRENT) {
                entityClass = HibernateCurrentStep.class;
            } else {
                entityClass = HibernateHistoryStep.class;
            }

            HibernateCriteriaBuilder builder = session.getCriteriaBuilder();
            CriteriaQuery<? extends HibernateStep> criteria = builder.createQuery(entityClass);
            criteria.where(buildExpression(query, builder, criteria.from(entityClass)));

            Set<Long> results = new HashSet<>();
            Query<? extends HibernateStep> dbQuery = session.createQuery(criteria);
            dbQuery.setCacheable(isCacheable());
            if (isCacheable()) {
                dbQuery.setCacheRegion(getCacheRegion());
            }

            for (HibernateStep step : dbQuery.list()) {
                results.add(step.getEntryId());
            }

            return new ArrayList<>(results);
        });
    }

    /*
     * (non-Javadoc)
     *
     * @see com.opensymphony.workflow.spi.WorkflowStore#query(com.opensymphony.workflow.query.WorkflowExpressionQuery)
     */
    public List<Long> query(final WorkflowExpressionQuery query) throws StoreException {
        return execute(session -> {
            com.opensymphony.workflow.query.Expression expression = query.getExpression();

            Class<?> entityClass = getQueryClass(expression, null);
            HibernateCriteriaBuilder builder = session.getCriteriaBuilder();
            CriteriaQuery<?> criteria = builder.createQuery(entityClass);
            Root<?> from = criteria.from(entityClass);
            Predicate expr;
            if (expression.isNested()) {
                expr = buildNested((NestedExpression) expression, builder, from);
            } else {
                expr = queryComparison((FieldExpression) expression, builder, from);
            }
            criteria.where(expr);

            Set<Long> results = new HashSet<>();
            Query<?> dbQuery = session.createQuery(criteria);
            dbQuery.setCacheable(isCacheable());
            if (isCacheable()) {
                dbQuery.setCacheRegion(getCacheRegion());
            }

            for (Object next : dbQuery.list()) {
                long item;

                if (next instanceof HibernateStep step) {
                    item = step.getEntryId();
                } else {
                    WorkflowEntry entry = (WorkflowEntry) next;
                    item = entry.getId();
                }

                results.add(item);
            }

            return new ArrayList<>(results);
        });
    }

    // Companion method of InternalCallback class
    protected abstract <T> T execute(InternalCallback<T> action) throws StoreException;

    protected String getCacheRegion() {
        return cacheRegion;
    }

    protected boolean isCacheable() {
        return cacheable;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    protected Predicate getExpression(final WorkflowQuery query, CriteriaBuilder builder, From<?, ?> from) throws StoreException {
        return execute(session -> {
            int operator = query.getOperator();

            return switch (operator) {
                case NOT_EQUALS -> builder.not(builder.like(from.get(getFieldName(query.getField())), (String) query.getValue()));
                case GT -> builder.greaterThan(from.get(getFieldName(query.getField())), (Comparable) query.getValue());
                case LT -> builder.lessThan(from.get(getFieldName(query.getField())), (Comparable) query.getValue());
                default -> builder.equal(from.get(getFieldName(query.getField())), query.getValue());
            };
        });
    }

    protected void delete(final Object entry) throws StoreException {
        execute(session -> {
            session.remove(entry);
            return null;
        });
    }

    // ~ DAO Methods ////////////////////////////////////////////////////////////////
    protected HibernateWorkflowEntry loadEntry(final long entryId) throws StoreException {
        return execute(session -> session.get(HibernateWorkflowEntry.class, entryId));
    }

    protected void save(final Object entry) throws StoreException {
        execute(session -> {
            session.merge(entry);
            return null;
        });
    }

    private String getFieldName(int field) {
        return switch (field) {
            case FieldExpression.ACTION -> "actionId";
            case FieldExpression.CALLER -> "caller";
            case FieldExpression.FINISH_DATE -> "finishDate";
            case FieldExpression.OWNER -> "owner";
            case FieldExpression.START_DATE -> "startDate";
            case FieldExpression.STEP -> "stepId";
            case FieldExpression.STATUS -> "status";
            case FieldExpression.STATE -> "state";
            case FieldExpression.NAME -> "workflowName";
            case FieldExpression.DUE_DATE -> "dueDate";
            default -> "1";
        };
    }

    private Class<?> getQueryClass(com.opensymphony.workflow.query.Expression expr, Collection<Class<?>> classesCache) {
        if (classesCache == null) {
            classesCache = new HashSet<>();
        }

        if (expr instanceof FieldExpression fieldExpression) {
            switch (fieldExpression.getContext()) {
                case FieldExpression.CURRENT_STEPS:
                    classesCache.add(HibernateCurrentStep.class);
                    break;
                case FieldExpression.HISTORY_STEPS:
                    classesCache.add(HibernateHistoryStep.class);
                    break;
                case FieldExpression.ENTRY:
                    classesCache.add(HibernateWorkflowEntry.class);
                    break;
                default:
                    throw new QueryNotSupportedException("Query for unsupported context " + fieldExpression.getContext());
            }
        } else {
            NestedExpression nestedExpression = (NestedExpression) expr;

            for (int i = 0; i < nestedExpression.getExpressionCount(); i++) {
                com.opensymphony.workflow.query.Expression expression = nestedExpression.getExpression(i);

                if (expression.isNested()) {
                    classesCache.add(getQueryClass(nestedExpression.getExpression(i), classesCache));
                } else {
                    classesCache.add(getQueryClass(expression, classesCache));
                }
            }
        }

        if (classesCache.size() > 1) {
            throw new QueryNotSupportedException("Store does not support nested queries of different types (types found:" + classesCache + ")");
        }

        return classesCache.iterator().next();
    }

    private Predicate buildExpression(WorkflowQuery query, CriteriaBuilder builder, From<?, ?> from) throws StoreException {
        if (query.getLeft() == null) {
            if (query.getRight() == null) {
                return getExpression(query, builder, from); // leaf node
            } else {
                throw new StoreException("Invalid WorkflowQuery object.  QueryLeft is null but QueryRight is not.");
            }
        } else {
            if (query.getRight() == null) {
                throw new StoreException("Invalid WorkflowQuery object.  QueryLeft is not null but QueryRight is.");
            }

            int operator = query.getOperator();
            WorkflowQuery left = query.getLeft();
            WorkflowQuery right = query.getRight();

            return switch (operator) {
                case AND -> builder.and(buildExpression(left, builder, from), buildExpression(right, builder, from));
                case OR -> builder.or(buildExpression(left, builder, from), buildExpression(right, builder, from));
                case XOR -> throw new QueryNotSupportedException("XOR Operator in Queries not supported by " + this.getClass().getName());
                default -> throw new QueryNotSupportedException("Operator '" + operator + "' is not supported by " + this.getClass().getName());
            };
        }
    }

    private Predicate buildNested(NestedExpression nestedExpression, CriteriaBuilder builder, From<?, ?> from) throws StoreException {
        Predicate full = null;

        for (int i = 0; i < nestedExpression.getExpressionCount(); i++) {
            Predicate expr;
            com.opensymphony.workflow.query.Expression expression = nestedExpression.getExpression(i);

            if (expression.isNested()) {
                expr = buildNested((NestedExpression) nestedExpression.getExpression(i),  builder, from);
            } else {
                FieldExpression sub = (FieldExpression) nestedExpression.getExpression(i);
                expr = queryComparison(sub, builder, from);

                if (sub.isNegate()) {
                    expr = builder.not(expr);
                }
            }

            if (full == null) {
                full = expr;
            } else {
                full = switch (nestedExpression.getExpressionOperator()) {
                    case NestedExpression.AND -> builder.and(full, expr);
                    case NestedExpression.OR -> builder.or(full, expr);
                    default -> full;
                };
            }
        }

        return full;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Predicate queryComparison(FieldExpression expression, CriteriaBuilder builder, From<?, ?> from) {
        int operator = expression.getOperator();

        return switch (operator) {
            case FieldExpression.NOT_EQUALS -> builder.not(builder.like(from.get(getFieldName(expression.getField())), (String) expression.getValue()));
            case FieldExpression.GT -> builder.greaterThan(from.get(getFieldName(expression.getField())), (Comparable) expression.getValue());
            case FieldExpression.LT -> builder.lessThan(from.get(getFieldName(expression.getField())), (Comparable) expression.getValue());
            default -> builder.equal(from.get(getFieldName(expression.getField())), expression.getValue());
        };
    }

    //~ Inner Interfaces ///////////////////////////////////////////////////////

    // ~ Internal Interfaces /////////////////////////////////////////////////////
    // Template method pattern to delegate implementation of Session
    // management to subclasses
    protected interface InternalCallback<T> {
        T doInHibernate(Session session) throws HibernateException, StoreException;
    }
}
