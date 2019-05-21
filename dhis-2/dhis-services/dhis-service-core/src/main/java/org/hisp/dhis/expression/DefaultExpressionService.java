package org.hisp.dhis.expression;

/*
 * Copyright (c) 2004-2019, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import static org.hisp.dhis.common.DimensionItemType.*;
import static org.hisp.dhis.expression.MissingValueStrategy.*;
import static org.hisp.dhis.parser.expression.ParserUtils.*;
import static org.hisp.dhis.parser.expression.antlr.ExpressionParser.*;
import static org.hisp.dhis.system.util.MathUtils.calculateExpression;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import static com.google.common.base.Preconditions.checkNotNull;
import java.util.stream.Stream;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hisp.dhis.category.CategoryOptionCombo;
import org.hisp.dhis.category.CategoryService;
import org.hisp.dhis.common.*;
import org.hisp.dhis.common.exception.InvalidIdentifierReferenceException;
import org.hisp.dhis.commons.collection.CachingMap;
import org.hisp.dhis.commons.util.TextUtils;
import org.hisp.dhis.constant.Constant;
import org.hisp.dhis.constant.ConstantService;
import org.hisp.dhis.dataelement.DataElement;
import org.hisp.dhis.dataelement.DataElementOperand;
import org.hisp.dhis.dataelement.DataElementService;
import org.hisp.dhis.expression.item.*;
import org.hisp.dhis.hibernate.HibernateGenericStore;
import org.hisp.dhis.indicator.Indicator;
import org.hisp.dhis.indicator.IndicatorValue;
import org.hisp.dhis.organisationunit.OrganisationUnitGroup;
import org.hisp.dhis.organisationunit.OrganisationUnitGroupService;
import org.hisp.dhis.parser.expression.*;
import org.hisp.dhis.parser.expression.item.ItemConstant;
import org.hisp.dhis.period.Period;
import org.hisp.dhis.system.jep.CustomFunctions;
import org.hisp.dhis.system.util.ExpressionUtils;
import org.hisp.dhis.system.util.MathUtils;
import org.hisp.dhis.util.DateUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The expression is a string describing a formula containing data element ids
 * and category option combo ids. The formula can potentially contain references
 * to data element totals.
 *
 * @author Margrethe Store
 * @author Lars Helge Overland
 * @author Jim Grace
 */
@Service( "org.hisp.dhis.expression.ExpressionService" )
public class DefaultExpressionService
    implements ExpressionService
{
    private static final Log log = LogFactory.getLog( DefaultExpressionService.class );

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final HibernateGenericStore<Expression> expressionStore;

    private final DataElementService dataElementService;

    private final ConstantService constantService;

    private final CategoryService categoryService;

    private final OrganisationUnitGroupService organisationUnitGroupService;

    private final DimensionService dimensionService;

    private final IdentifiableObjectManager idObjectManager;

    private final static ImmutableMap<Integer, ExprItem> EXPRESSION_ITEMS = ImmutableMap.<Integer, ExprItem>builder()

        .put(HASH_BRACE, new DimItemDataElementAndOperand() )
        .put(A_BRACE, new DimItemProgramAttribute() )
        .put(C_BRACE, new ItemConstant() )
        .put(D_BRACE, new DimItemProgramDataElement() )
        .put(I_BRACE, new DimItemProgramIndicator() )
        .put(N_BRACE, new DimItemIndicator() )
        .put(OUG_BRACE, new ItemOrgUnitGroup() )
        .put(R_BRACE, new DimItemReportingRate() )
        .put(DAYS, new ItemDays() )

        .build();

    public DefaultExpressionService(
        @Qualifier( "org.hisp.dhis.expression.ExpressionStore" ) HibernateGenericStore<Expression> expressionStore,
        DataElementService dataElementService, ConstantService constantService, CategoryService categoryService,
        OrganisationUnitGroupService organisationUnitGroupService, DimensionService dimensionService,
        IdentifiableObjectManager idObjectManager )
    {
        checkNotNull(expressionStore);
        checkNotNull(dataElementService);
        checkNotNull(constantService);
        checkNotNull(categoryService);
        checkNotNull(organisationUnitGroupService);
        checkNotNull(dimensionService);
        checkNotNull(idObjectManager);

        this.expressionStore = expressionStore;
        this.dataElementService = dataElementService;
        this.constantService = constantService;
        this.categoryService = categoryService;
        this.organisationUnitGroupService = organisationUnitGroupService;
        this.dimensionService = dimensionService;
        this.idObjectManager = idObjectManager;
    }

    // -------------------------------------------------------------------------
    // Expression CRUD operations
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public long addExpression( Expression expression )
    {
        expressionStore.save( expression );

        return expression.getId();
    }

    @Override
    @Transactional
    public void deleteExpression( Expression expression )
    {
        expressionStore.delete( expression );
    }

    @Override
    @Transactional(readOnly = true)
    public Expression getExpression( long id )
    {
        return expressionStore.get( id );
    }

    @Override
    @Transactional
    public void updateExpression( Expression expression )
    {
        expressionStore.update( expression );
    }

    @Override
    @Transactional(readOnly = true)
    public List<Expression> getAllExpressions()
    {
        return expressionStore.getAll();
    }

    // -------------------------------------------------------------------------
    // Indicator expression logic
    // -------------------------------------------------------------------------

    @Override
    public Set<DimensionalItemObject> getIndicatorDimensionalItemObjects( Collection<Indicator> indicators )
    {
        Set<DimensionalItemId> itemIds = indicators.stream()
            .flatMap( i -> Stream.of( i.getNumerator(), i.getDenominator() ) )
            .map( this::getExpressionDimensionalItemIds )
            .flatMap( Set::stream )
            .collect( Collectors.toSet() );

        return dimensionService.getDataDimensionalItemObjects( itemIds );
    }

    @Override
    public Set<OrganisationUnitGroup> getIndicatorOrgUnitGroups( Collection<Indicator> indicators )
    {
        Set<OrganisationUnitGroup> groups = new HashSet<>();

        if ( indicators != null )
        {
            for ( Indicator indicator : indicators )
            {
                groups.addAll( getExpressionOrgUnitGroups( indicator.getNumerator() ) );
                groups.addAll( getExpressionOrgUnitGroups( indicator.getDenominator() ) );
            }
        }

        return groups;
    }
    
    public IndicatorValue getIndicatorValueObject( Indicator indicator, List<Period> periods,
        Map<DimensionalItemObject, Double> valueMap, Map<String, Double> constantMap,
        Map<String, Integer> orgUnitCountMap )
    {
        if ( indicator == null || indicator.getNumerator() == null || indicator.getDenominator() == null )
        {
            return null;
        }

        Integer days = periods != null ? getDaysFromPeriods( periods ) : null;

        Double denominatorValue = getExpressionValue( indicator.getDenominator(),
            valueMap, constantMap, orgUnitCountMap, days, MissingValueStrategy.NEVER_SKIP );

        Double numeratorValue = getExpressionValue( indicator.getNumerator(),
            valueMap, constantMap, orgUnitCountMap, days, MissingValueStrategy.NEVER_SKIP );

        if ( denominatorValue != null && denominatorValue != 0d && numeratorValue != null )
        {
            int multiplier = indicator.getIndicatorType().getFactor();

            int divisor = 1;

            if ( indicator.isAnnualized() && periods != null )
            {
                final int daysInPeriod = getDaysFromPeriods( periods );

                multiplier *= DateUtils.DAYS_IN_YEAR;

                divisor = daysInPeriod;
            }

            return new IndicatorValue()
                .setNumeratorValue( numeratorValue )
                .setDenominatorValue( denominatorValue )
                .setMultiplier( multiplier )
                .setDivisor( divisor );
        }

        return null;
    }

    @Override
    public ExpressionValidationOutcome indicatorExpressionIsValid( String expression )
    {
        try
        {
            getIndicatorExpressionDescription( expression );

            return ExpressionValidationOutcome.VALID;
        }
        catch ( IllegalStateException e )
        {
            return ExpressionValidationOutcome.EXPRESSION_IS_NOT_WELL_FORMED;
        }
    }

    @Override
    public String getIndicatorExpressionDescription( String expression )
    {
        if ( expression == null )
        {
            return "";
        }

        CommonExpressionVisitor visitor = newVisitor( FUNCTION_EVALUATE_ALL_PATHS, ITEM_GET_DESCRIPTIONS );

        visit( expression, visitor, false );

        Map<String, String> itemDescriptions = visitor.getItemDescriptions();

        String description = expression;

        for ( Map.Entry<String, String> entry : itemDescriptions.entrySet() )
        {
            description = description.replace( entry.getKey(), entry.getValue() );
        }

        return description;
    }

    // -------------------------------------------------------------------------
    // Expression logic
    // -------------------------------------------------------------------------

    @Override
    public Set<DimensionalItemObject> getExpressionDimensionalItemObjects( String expression )
    {
        Set<DimensionalItemId> itemIds = getExpressionDimensionalItemIds( expression );

        return dimensionService.getDataDimensionalItemObjects( itemIds );
    }

    @Override
    public Set<DimensionalItemId> getExpressionDimensionalItemIds( String expression )
    {
        if ( expression == null )
        {
            return new HashSet<>();
        }

        CommonExpressionVisitor visitor = newVisitor( FUNCTION_EVALUATE_ALL_PATHS, ITEM_GET_IDS );

        visit( expression, visitor, true );

        return visitor.getItemIds();
    }

    @Override
    public Set<OrganisationUnitGroup> getExpressionOrgUnitGroups( String expression )
    {
        if ( expression == null )
        {
            return new HashSet<>();
        }

        CommonExpressionVisitor visitor = newVisitor( FUNCTION_EVALUATE_ALL_PATHS, ITEM_GET_ORG_UNIT_GROUPS );

        visit( expression, visitor, true );

        Set<String> orgUnitGroupIds = visitor.getOrgUnitGroupIds();

        return orgUnitGroupIds.stream()
            .map(organisationUnitGroupService::getOrganisationUnitGroup)
            .collect( Collectors.toSet() );
    }

    @Override
    public Double getExpressionValue( String expression,
        Map<DimensionalItemObject, Double> valueMap, Map<String, Double> constantMap,
        Map<String, Integer> orgUnitCountMap, Integer days,
        MissingValueStrategy missingValueStrategy )
    {
        if ( expression == null )
        {
            return null;
        }

        CommonExpressionVisitor expressionExprVisitor = newVisitor(
            FUNCTION_EVALUATE, ITEM_EVALUATE );

        Map<String, Double> keyValueMap = valueMap.entrySet().stream().collect(
            Collectors.toMap( e -> e.getKey().getDimensionItem(), Map.Entry::getValue) );

        expressionExprVisitor.setKeyValueMap( keyValueMap );
        expressionExprVisitor.setConstantMap( constantMap );
        expressionExprVisitor.setOrgUnitCountMap( orgUnitCountMap );

        if ( days != null )
        {
            expressionExprVisitor.setDays( Double.valueOf( days ) );
        }

        Double value = visit ( expression, expressionExprVisitor, true );

        int itemsFound = expressionExprVisitor.getItemsFound();
        int itemValuesFound = expressionExprVisitor.getItemValuesFound();

        switch ( missingValueStrategy )
        {
            case SKIP_IF_ANY_VALUE_MISSING:
                if ( itemValuesFound < itemsFound )
                {
                    return null;
                }

            case SKIP_IF_ALL_VALUES_MISSING:
                if ( itemsFound != 0 && itemValuesFound == 0 )
                {
                    return null;
                }

            case NEVER_SKIP:
                if ( value == null )
                {
                    return 0d;
                }
        }

        return value;
    }

    // -------------------------------------------------------------------------
    // Supportive methods
    // -------------------------------------------------------------------------

    /**
     * Creates a new ExpressionItemsVisitor object.
     */
    private CommonExpressionVisitor newVisitor( ExprFunctionMethod functionMethod, ExprItemMethod itemMethod )
    {
        return CommonExpressionVisitor.newBuilder()
            .withFunctionMap( COMMON_EXPRESSION_FUNCTIONS )
            .withItemMap( EXPRESSION_ITEMS )
            .withFunctionMethod( functionMethod )
            .withItemMethod( itemMethod )
            .withConstantService( constantService )
            .withDimensionService( dimensionService )
            .withOrganisationUnitGroupService( organisationUnitGroupService )
            .buildForExpressions();
    }

    private Double visit( String expression, CommonExpressionVisitor visitor, boolean logWarnings )
    {
        try
        {
            return castDouble( Parser.visit( expression, visitor ) );
        }
        catch ( ParserException ex )
        {
            String message = ex.getMessage() + " parsing expression '" + expression + "'";

            if ( logWarnings )
            {
                log.warn( message );
            }
            else
            {
                throw new ParserException( message );
            }
        }

        return DOUBLE_VALUE_IF_NULL;
    }

    // -------------------------------------------------------------------------
    // Expression logic based on regular expressions (to be refactored)
    // -------------------------------------------------------------------------

    @Override
    public Double getExpressionValueRegEx( Expression expression, Map<? extends DimensionalItemObject, Double> valueMap,
        Map<String, Double> constantMap, Map<String, Integer> orgUnitCountMap, Integer days )
    {
        return getExpressionValueRegEx( expression, valueMap, constantMap, orgUnitCountMap, days, null );

    }

    @Override
    public Double getExpressionValueRegEx( Expression expression, Map<? extends DimensionalItemObject, Double> valueMap,
        Map<String, Double> constantMap, Map<String, Integer> orgUnitCountMap, Integer days,
        ListMap<String, Double> aggregateMap )
    {
        String expressionString = generateExpression( expression.getExpression(),
            valueMap, constantMap, orgUnitCountMap, days, expression.getMissingValueStrategy(),
            aggregateMap );

        return expressionString != null ? calculateExpression( expressionString ) : null;
    }

    @Override
    public Set<DataElement> getDataElementsInExpression( String expression )
    {
        return getIdObjectsInExpression( OPERAND_PATTERN, expression,
            ( m ) -> dataElementService.getDataElement( m.group( GROUP_DATA_ELEMENT ) ) );
    }

    @Override
    public Set<CategoryOptionCombo> getOptionCombosInExpression( String expression )
    {
        return getIdObjectsInExpression( CATEGORY_OPTION_COMBO_OPERAND_PATTERN, expression,
            ( m ) -> categoryService.getCategoryOptionCombo( m.group( GROUP_CATEGORORY_OPTION_COMBO ) ) );
    }

    @Override
    public Set<OrganisationUnitGroup> getOrganisationUnitGroupsInExpression( String expression )
    {
        return getIdObjectsInExpression( OU_GROUP_PATTERN, expression,
            ( m ) -> organisationUnitGroupService.getOrganisationUnitGroup( m.group( GROUP_ID ) ) );
    }

    /**
     * Returns a set of identifiable objects which are referenced in
     * the given expression based on the given regular expression pattern.
     *
     * @param pattern the regular expression pattern to match identifiable objects on.
     * @param expression the expression where identifiable objects are referenced.
     * @param provider the provider of identifiable objects, accepts a matcher and
     *        provides the object.
     * @return a set of identifiable objects.
     */
    private <T extends IdentifiableObject> Set<T> getIdObjectsInExpression( Pattern pattern, String expression, Function<Matcher, T> provider )
    {
        Set<T> objects = new HashSet<>();

        if ( expression == null )
        {
            return  objects;
        }

        final Matcher matcher = pattern.matcher( expression );

        while ( matcher.find() )
        {
            final T object = provider.apply( matcher );

            if ( object != null )
            {
                objects.add( object );
            }
        }

        return objects;
    }

    @Override
    @Transactional
    public Set<DataElementOperand> getOperandsInExpression( String expression )
    {
        Set<DataElementOperand> operandsInExpression = new HashSet<>();

        if ( expression != null )
        {
            final Matcher matcher = OPERAND_PATTERN.matcher( expression );

            while ( matcher.find() )
            {
                String dataElementUid = StringUtils.trimToNull( matcher.group( GROUP_DATA_ELEMENT ) );
                String optionComboUid = StringUtils.trimToNull( matcher.group( GROUP_CATEGORORY_OPTION_COMBO ) );
                DataElement dataElement = dataElementService.getDataElement( dataElementUid );
                CategoryOptionCombo optionCombo = optionComboUid == null ? null :
                    categoryService.getCategoryOptionCombo( optionComboUid );

                operandsInExpression.add ( new DataElementOperand( dataElement, optionCombo ) );
            }
        }

        return operandsInExpression;
    }

    @Override
    @Transactional
    public void getAggregatesAndNonAggregatesInExpression( String expression,
        Set<String> aggregates, Set<String> nonAggregates )
    {
        Pattern prefix = CustomFunctions.AGGREGATE_PATTERN_PREFIX;

        if ( expression != null )
        {
            final Matcher matcher = prefix.matcher( expression );

            int scan = 0;
            int len = expression.length();

            while ( (scan < len) && (matcher.find( scan )) )
            {
                int start = matcher.end();
                int end = Expression.matchExpression( expression, start );

                if ( end < 0 )
                {
                    log.warn( "Bad expression starting at " + start + " in " + expression );
                }
                else if ( end > 0 )
                {
                    nonAggregates.add( expression.substring( scan, matcher.start() ) );
                    aggregates.add( expression.substring( start, end ) );
                    scan = end + 1;
                }
                else
                {
                    scan = start + 1;
                }
            }

            if ( scan < len )
            {
                nonAggregates.add( expression.substring( scan, len ) );
            }
        }
    }

    @Override
    public Set<String> getElementsAndOptionCombosInExpression( String expression )
    {
        Set<String> elementsAndCombos = new HashSet<>();

        if ( expression == null || expression.isEmpty() )
        {
            return elementsAndCombos;
        }

        Matcher matcher = OPERAND_PATTERN.matcher( expression );

        while ( matcher.find() )
        {
            String elementAndCombo = matcher.group( 1 );

            if ( matcher.group( 2 ) != null && !matcher.group( 2 ).equals( SYMBOL_WILDCARD ) )
            {
                elementAndCombo += matcher.group( 2 );
            }

            elementsAndCombos.add( elementAndCombo );
        }

        return elementsAndCombos;
    }

    @Override
    public Set<DimensionalItemId> getDimensionalItemIdsInExpression( String expression )
    {
        Set<DimensionalItemId> itemIds = new HashSet<>();

        if ( expression == null || expression.isEmpty() )
        {
            return itemIds;
        }

        Matcher matcher = VARIABLE_PATTERN.matcher( expression );

        while ( matcher.find() )
        {
            String key = matcher.group( GROUP_KEY );
            String id1 = matcher.group( GROUP_ID1 );
            String id2 = matcher.group( GROUP_ID2 );
            String id3 = matcher.group( GROUP_ID3 );

            DimensionItemType itemType =
                "#".equals( key ) ? id2 == null && id3 == null ? DATA_ELEMENT : DATA_ELEMENT_OPERAND :
                "D".equals( key ) ? PROGRAM_DATA_ELEMENT :
                "A".equals( key ) ? PROGRAM_ATTRIBUTE :
                "I".equals( key ) ? PROGRAM_INDICATOR :
                "R".equals( key ) ? REPORTING_RATE : null;

            if ( itemType != null )
            {
                itemIds.add( new DimensionalItemId( itemType, id1, id2, id3 ) );
            }
        }

        return itemIds;
    }

    @Override
    public Set<DimensionalItemObject> getDimensionalItemObjectsInExpression( String expression )
    {
        Set<DimensionalItemId> itemIds = getDimensionalItemIdsInExpression( expression );

        return dimensionService.getDataDimensionalItemObjects( itemIds );
    }

    @Override
    @Transactional
    public ExpressionValidationOutcome predictorExpressionIsValid( String expression )
    {
        return expressionIsValid( expression, true );
    }

    @Override
    @Transactional
    public ExpressionValidationOutcome validationRuleExpressionIsValid( String expression )
    {
        return expressionIsValid( expression, false );
    }

    private ExpressionValidationOutcome expressionIsValid( String expression, boolean customFunctions )
    {
        if ( expression == null || expression.isEmpty() )
        {
            return ExpressionValidationOutcome.EXPRESSION_IS_EMPTY;
        }

        // ---------------------------------------------------------------------
        // Operands
        // ---------------------------------------------------------------------

        StringBuffer sb = new StringBuffer();
        Matcher matcher = VARIABLE_PATTERN.matcher( expression );

        while ( matcher.find() )
        {
            String dimensionItem = matcher.group( GROUP_ID );

            if ( dimensionService.getDataDimensionalItemObject( dimensionItem ) == null )
            {
                return ExpressionValidationOutcome.DIMENSIONAL_ITEM_OBJECT_DOES_NOT_EXIST;
            }

            matcher.appendReplacement( sb, "1.1" );
        }

        expression = TextUtils.appendTail( matcher, sb );

        // ---------------------------------------------------------------------
        // Constants
        // ---------------------------------------------------------------------

        matcher = CONSTANT_PATTERN.matcher( expression );
        sb = new StringBuffer();

        while ( matcher.find() )
        {
            String constant = matcher.group( GROUP_ID );

            if ( idObjectManager.getNoAcl( Constant.class, constant ) == null )
            {
                return ExpressionValidationOutcome.CONSTANT_DOES_NOT_EXIST;
            }

            matcher.appendReplacement( sb, "1.1" );
        }

        expression = TextUtils.appendTail( matcher, sb );

        // ---------------------------------------------------------------------
        // Org unit groups
        // ---------------------------------------------------------------------

        matcher = OU_GROUP_PATTERN.matcher( expression );
        sb = new StringBuffer();

        while ( matcher.find() )
        {
            String group = matcher.group( GROUP_ID );

            if ( idObjectManager.getNoAcl( OrganisationUnitGroup.class, group ) == null )
            {
                return ExpressionValidationOutcome.ORG_UNIT_GROUP_DOES_NOT_EXIST;
            }

            matcher.appendReplacement( sb, "1.1" );
        }

        expression = TextUtils.appendTail( matcher, sb );

        // ---------------------------------------------------------------------
        // Days
        // ---------------------------------------------------------------------

        expression = expression.replaceAll( DAYS_EXPRESSION, "1.1" );

        // ---------------------------------------------------------------------
        // Well-formed expression
        // ---------------------------------------------------------------------

        if ( MathUtils.expressionHasErrors( expression, customFunctions ) )
        {
            return ExpressionValidationOutcome.EXPRESSION_IS_NOT_WELL_FORMED;
        }

        return ExpressionValidationOutcome.VALID;
    }

    @Override
    @Transactional
    public String getExpressionDescriptionRegEx( String expression )
    {
        if ( expression == null || expression.isEmpty() )
        {
            return null;
        }

        // ---------------------------------------------------------------------
        // Operands
        // ---------------------------------------------------------------------

        StringBuffer sb = new StringBuffer();
        Matcher matcher = VARIABLE_PATTERN.matcher( expression );

        while ( matcher.find() )
        {
            String dimensionItem = matcher.group( GROUP_ID );

            DimensionalItemObject dimensionItemObject = dimensionService.getDataDimensionalItemObject( dimensionItem );

            if ( dimensionItemObject == null )
            {
                throw new InvalidIdentifierReferenceException( "Identifier does not reference a dimensional item object: " + dimensionItem );
            }

            matcher.appendReplacement( sb, Matcher.quoteReplacement( dimensionItemObject.getDisplayName() ) );
        }

        expression = TextUtils.appendTail( matcher, sb );

        // ---------------------------------------------------------------------
        // Constants
        // ---------------------------------------------------------------------

        sb = new StringBuffer();
        matcher = CONSTANT_PATTERN.matcher( expression );

        while ( matcher.find() )
        {
            String co = matcher.group( GROUP_ID );

            Constant constant = constantService.getConstant( co );

            if ( constant == null )
            {
                throw new InvalidIdentifierReferenceException( "Identifier does not reference a constant: " + co );
            }

            matcher.appendReplacement( sb, Matcher.quoteReplacement( constant.getDisplayName() ) );
        }

        expression = TextUtils.appendTail( matcher, sb );

        // ---------------------------------------------------------------------
        // Org unit groups
        // ---------------------------------------------------------------------

        sb = new StringBuffer();
        matcher = OU_GROUP_PATTERN.matcher( expression );

        while ( matcher.find() )
        {
            String oug = matcher.group( GROUP_ID );

            OrganisationUnitGroup group = organisationUnitGroupService.getOrganisationUnitGroup( oug );

            if ( group == null )
            {
                throw new InvalidIdentifierReferenceException( "Identifier does not reference an organisation unit group: " + oug );
            }

            matcher.appendReplacement( sb, Matcher.quoteReplacement( group.getDisplayName() ) );
        }

        expression = TextUtils.appendTail( matcher, sb );

        // ---------------------------------------------------------------------
        // Days
        // ---------------------------------------------------------------------

        sb = new StringBuffer();
        matcher = DAYS_PATTERN.matcher( expression );

        while ( matcher.find() )
        {
            matcher.appendReplacement( sb, DAYS_DESCRIPTION );
        }

        expression = TextUtils.appendTail( matcher, sb );

        return expression;
    }

    @Override
    @Transactional
    public void substituteExpressions( Collection<Indicator> indicators, Integer days )
    {
        if ( indicators != null && !indicators.isEmpty() )
        {
            Map<String, Constant> constants = new CachingMap<String, Constant>()
                .load( idObjectManager.getAllNoAcl( Constant.class ), BaseIdentifiableObject::getUid);

            Map<String, OrganisationUnitGroup> orgUnitGroups = new CachingMap<String, OrganisationUnitGroup>()
                .load( idObjectManager.getAllNoAcl( OrganisationUnitGroup.class ), BaseIdentifiableObject::getUid);

            for ( Indicator indicator : indicators )
            {
                indicator.setExplodedNumerator( substituteExpression(
                    indicator.getNumerator(), constants, orgUnitGroups, days ) );
                indicator.setExplodedDenominator( substituteExpression(
                    indicator.getDenominator(), constants, orgUnitGroups, days ) );
            }
        }
    }

    private String substituteExpression( String expression, Map<String, Constant> constants,
        Map<String, OrganisationUnitGroup> orgUnitGroups, Integer days )
    {
        if ( expression == null || expression.isEmpty() )
        {
            return null;
        }

        // ---------------------------------------------------------------------
        // Constants
        // ---------------------------------------------------------------------

        StringBuffer sb = new StringBuffer();
        Matcher matcher = CONSTANT_PATTERN.matcher( expression );

        while ( matcher.find() )
        {
            String co = matcher.group( GROUP_ID );

            Constant constant = constants.get( co );

            String replacement = constant != null ? String.valueOf( constant.getValue() ) : NULL_REPLACEMENT;

            matcher.appendReplacement( sb, Matcher.quoteReplacement( replacement ) );
        }

        expression = TextUtils.appendTail( matcher, sb );

        // ---------------------------------------------------------------------
        // Org unit groups
        // ---------------------------------------------------------------------

        sb = new StringBuffer();
        matcher = OU_GROUP_PATTERN.matcher( expression );

        while ( matcher.find() )
        {
            String oug = matcher.group( GROUP_ID );

            OrganisationUnitGroup group = orgUnitGroups.get( oug );

            String replacement = group != null ? String.valueOf( group.getMembers().size() ) : NULL_REPLACEMENT;

            matcher.appendReplacement( sb, replacement );

            // TODO sub tree
        }

        expression = TextUtils.appendTail( matcher, sb );

        // ---------------------------------------------------------------------
        // Days
        // ---------------------------------------------------------------------

        sb = new StringBuffer();
        matcher = DAYS_PATTERN.matcher( expression );

        while ( matcher.find() )
        {
            String replacement = days != null ? String.valueOf( days ) : NULL_REPLACEMENT;

            matcher.appendReplacement( sb, replacement );
        }

        return TextUtils.appendTail( matcher, sb );
    }

    @Override
    public String generateExpression( String expression, Map<? extends DimensionalItemObject, Double> valueMap,
        Map<String, Double> constantMap, Map<String, Integer> orgUnitCountMap, Integer days,
        MissingValueStrategy missingValueStrategy )
    {
        return generateExpression( expression, valueMap, constantMap, orgUnitCountMap, days, missingValueStrategy, null );
    }

    /**
     * Generates an expression based on the given data maps.
     *
     * @param expression the expression.
     * @param valueMap the value map.
     * @param constantMap the constant map.
     * @param orgUnitCountMap the organisation unit count map.
     * @param days the number of days.
     * @param missingValueStrategy the missing value strategy.
     * @param aggregateMap the aggregate map.
     * @return an expression.
     */
    private String generateExpression( String expression, Map<? extends DimensionalItemObject, Double> valueMap,
        Map<String, Double> constantMap, Map<String, Integer> orgUnitCountMap, Integer days,
        MissingValueStrategy missingValueStrategy,
        Map<String, List<Double>> aggregateMap )
    {
        if ( expression == null || expression.isEmpty() )
        {
            return null;
        }

        expression = ExpressionUtils.normalizeExpression( expression );

        Map<String, Double> dimensionItemValueMap = valueMap.entrySet().stream().
            filter( e -> e.getValue() != null ).
            collect( Collectors.toMap( e -> e.getKey().getDimensionItem(), Map.Entry::getValue) );

        missingValueStrategy = ObjectUtils.firstNonNull( missingValueStrategy, NEVER_SKIP );

        // ---------------------------------------------------------------------
        // Aggregates
        // ---------------------------------------------------------------------

        StringBuffer sb = new StringBuffer();

        Pattern prefix = CustomFunctions.AGGREGATE_PATTERN_PREFIX;
        Matcher matcher = prefix.matcher( expression );

        int scan = 0, len = expression.length(), tail = 0;

        while ( scan < len && matcher.find( scan ) )
        {
            int start = matcher.end();
            int end = Expression.matchExpression( expression, start );

            sb.append(expression, scan, matcher.start());
            sb.append( expression.substring( matcher.start(), start ).toUpperCase() );

            if ( end < 0 )
            {
                scan = start + 1;
                tail = start;
            }
            else if ( aggregateMap == null || expression.charAt( start ) == '<' )
            {
                sb.append(expression, start, end);
                scan = end + 1;
                tail = end;
            }
            else
            {
                String subExpression = expression.substring( start, end );
                List<Double> samples = aggregateMap.get( subExpression );

                if ( samples == null )
                {
                    if ( SKIP_IF_ANY_VALUE_MISSING.equals( missingValueStrategy ) )
                    {
                        return null;
                    }
                }
                else
                {
                    String literal = samples.toString();
                    sb.append( literal );
                }

                scan = end;
                tail = end;
            }
        }

        sb.append( expression.substring( tail ) );
        expression = sb.toString();

        // ---------------------------------------------------------------------
        // IsNull function (implemented here)
        // ---------------------------------------------------------------------

        sb = new StringBuffer();
        matcher = ISNULL_PATTERN.matcher( expression );

        scan = 0;
        len = expression.length();
        List<String> isNullArgList = new ArrayList<>();

        while ( scan < len && matcher.find( scan ) )
        {
            int start = matcher.end();
            int end = Expression.matchExpression( expression, start );

            sb.append(expression, scan, matcher.start());

            scan = start + 1;

            if ( end > 0 )
            {
                String arg = expression.substring( start, end );
                Matcher argMatcher = VARIABLE_PATTERN.matcher( arg );

                if ( argMatcher.find() )
                {
                    String dimItem = argMatcher.group( GROUP_ID );

                    final Double value = dimensionItemValueMap.get( dimItem );

                    if ( value == null )
                    {
                        sb.append( TRUE_VALUE );
                        isNullArgList.add( arg.trim() );
                    }
                    else
                    {
                        sb.append( FALSE_VALUE );
                    }

                    scan = end + 1;
                }
            }
        }

        sb.append( expression.substring( scan ) );
        expression = sb.toString();

        // Replace any other instances of the isNull() args with zeros, to
        // avoid the expression being disqualified because they are there.
        for( String isNullArg : isNullArgList )
        {
            expression = expression.replace(isNullArg, "0" );
        }

        // ---------------------------------------------------------------------
        // Other scalar custom functions (make them case-insensitive)
        // ---------------------------------------------------------------------

        sb = new StringBuffer();
        matcher = CustomFunctions.SCALAR_PATTERN_PREFIX.matcher( expression );

        while ( matcher.find() )
        {
            matcher.appendReplacement( sb,
                expression.substring( matcher.start(), matcher.end() ).toUpperCase() );
        }

        expression = TextUtils.appendTail( matcher, sb );

        // ---------------------------------------------------------------------
        // DimensionalItemObjects
        // ---------------------------------------------------------------------

        sb = new StringBuffer();
        matcher = VARIABLE_PATTERN.matcher( expression );

        int matchCount = 0;
        int valueCount = 0;

        while ( matcher.find() )
        {
            matchCount++;

            String dimItem = matcher.group( GROUP_ID );

            final Double value = dimensionItemValueMap.get( dimItem );

            boolean missingValue = value == null;

            if ( missingValue && SKIP_IF_ANY_VALUE_MISSING.equals( missingValueStrategy ) )
            {
                return null;
            }

            if ( !missingValue )
            {
                valueCount++;
            }

            String replacement = value != null ? String.valueOf( value ) : NULL_REPLACEMENT;

            matcher.appendReplacement( sb, Matcher.quoteReplacement( replacement ) );
        }

        if ( SKIP_IF_ALL_VALUES_MISSING.equals( missingValueStrategy ) && matchCount > 0 && valueCount == 0 )
        {
            return null;
        }

        expression = TextUtils.appendTail( matcher, sb );

        // ---------------------------------------------------------------------
        // Constants
        // ---------------------------------------------------------------------

        sb = new StringBuffer();
        matcher = CONSTANT_PATTERN.matcher( expression );

        while ( matcher.find() )
        {
            final Double constant = constantMap != null ? constantMap.get( matcher.group( GROUP_ID ) ) : null;

            String replacement = constant != null ? String.valueOf( constant ) : NULL_REPLACEMENT;

            matcher.appendReplacement( sb, replacement );
        }

        expression = TextUtils.appendTail( matcher, sb );

        // ---------------------------------------------------------------------
        // Org unit groups
        // ---------------------------------------------------------------------

        sb = new StringBuffer();
        matcher = OU_GROUP_PATTERN.matcher( expression );

        while ( matcher.find() )
        {
            final Integer count = orgUnitCountMap != null ? orgUnitCountMap.get( matcher.group( GROUP_ID ) ) : null;

            String replacement = count != null ? String.valueOf( count ) : NULL_REPLACEMENT;

            matcher.appendReplacement( sb, replacement );
        }

        expression = TextUtils.appendTail( matcher, sb );

        // ---------------------------------------------------------------------
        // Days
        // ---------------------------------------------------------------------

        sb = new StringBuffer();
        matcher = DAYS_PATTERN.matcher( expression );

        while ( matcher.find() )
        {
            String replacement = days != null ? String.valueOf( days ) : NULL_REPLACEMENT;

            matcher.appendReplacement( sb, replacement );
        }

        return TextUtils.appendTail( matcher, sb );
    }

    private int getDaysFromPeriods( List<Period> periods )
    {
        return periods.stream().mapToInt(Period::getDaysInPeriod).sum();
    }
}
