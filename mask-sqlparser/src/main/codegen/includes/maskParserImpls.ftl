<#-- mask 扩展产生式（spec §4.3）；由 config.fmpp implementationFiles 挂载 -->
SqlNode SqlMaskInsertOverwrite() :
{
    final SqlNodeList keywordList = new SqlNodeList(getPos());
    final SqlIdentifier tableName;
    SqlNode source;
    SqlNodeList columnList;
    final Pair<SqlNodeList, SqlNodeList> p;
    final Span s;
}
{
    <INSERT> { s = span(); }
    <OVERWRITE>
    {
        if (!(this.conformance instanceof SqlMaskConformance)
            || !((SqlMaskConformance) this.conformance).isInsertOverwriteAllowed()) {
            throw new ParseException("INSERT OVERWRITE is not enabled for this dialect");
        }
    }
    [ <TABLE> ]
    tableName = CompoundTableIdentifier()
    {
        if (getToken(1).kind == PARTITION) {
            throw new ParseException(
                "INSERT OVERWRITE ... PARTITION clause is not supported");
        }
        if (getToken(0).kind == IDENTIFIER
            && "DIRECTORY".equalsIgnoreCase(getToken(0).image)
            && getToken(1).kind == QUOTED_STRING) {
            throw new ParseException("INSERT OVERWRITE DIRECTORY is not supported");
        }
    }
    (
        LOOKAHEAD(2)
        p = ParenthesizedCompoundIdentifierList()
        {
            if (!p.right.isEmpty()) {
                throw new ParseException(
                    "compound insert columns are not supported");
            }
            columnList = p.left.isEmpty() ? null : p.left;
        }
    |   { columnList = null; }
    )
    source = OrderedQueryOrExpr(ExprContext.ACCEPT_QUERY)
    {
        return new SqlInsertOverwrite(s.end(source), keywordList, tableName, source,
            columnList);
    }
}

<#-- SELECT TOP (n)：映射进 SqlSelect.fetch（spec §4.2），由 Parser.jj SqlSelect() 挂点调用 -->
SqlNode SqlMaskTopN() :
{
    final SqlNode expr;
}
{
    <TOP>
    (
        LOOKAHEAD(2) <LPAREN> expr = Expression(ExprContext.ACCEPT_SUB_QUERY) <RPAREN>
    |   expr = UnsignedNumericLiteral()
    )
    {
        if (!(this.conformance instanceof SqlMaskConformance)
            || !((SqlMaskConformance) this.conformance).isTopNAllowed()) {
            throw new ParseException("TOP is not enabled for this dialect");
        }
        <#-- PERCENT 是保留字（自带 <PERCENT> token），永远以 kind=PERCENT 到达；
             IDENTIFIER 分支仅为防御性回退（与下方 TIES 同理） -->
        if (getToken(1).kind == PERCENT
            || (getToken(1).kind == IDENTIFIER && "PERCENT".equalsIgnoreCase(getToken(1).image))) {
            throw new ParseException("TOP ... PERCENT is not supported");
        }
        <#-- TIES 在 Calcite 基语法里已是非保留关键字（自带 <TIES> token），需兼容两种形态；
             IDENTIFIER 分支保留作为防御性回退 -->
        if (getToken(1).kind == WITH
            && (getToken(2).kind == TIES
                || (getToken(2).kind == IDENTIFIER && "TIES".equalsIgnoreCase(getToken(2).image)))) {
            throw new ParseException("TOP ... WITH TIES is not supported");
        }
        return expr;
    }
}
