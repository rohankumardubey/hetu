/*
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
package io.prestosql.security;

import io.prestosql.spi.connector.QualifiedObjectName;
import io.prestosql.spi.security.Identity;
import io.prestosql.spi.security.ViewExpression;
import io.prestosql.spi.type.Type;
import io.prestosql.transaction.TransactionId;

import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;

public class ViewAccessControl
        extends DenyAllAccessControl
{
    private final AccessControl delegate;

    public ViewAccessControl(AccessControl delegate)
    {
        this.delegate = requireNonNull(delegate, "delegate is null");
    }

    @Override
    public void checkCanSelectFromColumns(TransactionId transactionId, Identity identity, QualifiedObjectName tableName, Set<String> columnNames)
    {
        delegate.checkCanCreateViewWithSelectFromColumns(transactionId, identity, tableName, columnNames);
    }

    @Override
    public void checkCanCreateViewWithSelectFromColumns(TransactionId transactionId, Identity identity, QualifiedObjectName tableName, Set<String> columnNames)
    {
        delegate.checkCanCreateViewWithSelectFromColumns(transactionId, identity, tableName, columnNames);
    }

    @Override
    public List<ViewExpression> getRowFilters(TransactionId transactionId, Identity identity, QualifiedObjectName tableName)
    {
        return delegate.getRowFilters(transactionId, identity, tableName);
    }

    @Override
    public List<ViewExpression> getColumnMasks(TransactionId transactionId, Identity identity, QualifiedObjectName tableName, String columnName, Type type)
    {
        return delegate.getColumnMasks(transactionId, identity, tableName, columnName, type);
    }
}
