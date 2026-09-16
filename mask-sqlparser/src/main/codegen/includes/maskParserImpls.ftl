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
