# ddl-to-yaml.awk — TPC-DS PostgreSQL DDL → mask 元数据 YAML（25 表全列）
# 用法: awk -v mode=mask|rowfilter|both -f ddl-to-yaml.awk tpcds.sql > out.yaml
#   mode=mask      仅列脱敏绑定（无 rowFilter）
#   mode=rowfilter 仅行过滤（无列绑定；date_dim/customer/customer_address 注入谓词）
#   mode=both      两者同时
# catalog=tpcds schema=public，与语料中的三段名/搜索路径引用一致。

BEGIN {
    print "# 由 TPC-DS PostgreSQL DDL 生成：25 表全列，catalog=tpcds schema=public"
    print "metadata:"
    print "  tables:"
}
/^create table /    { name = $3; intable = (name != "dbgen_version"); if (intable) {
                          print "    - catalog: tpcds"
                          print "      schema: public"
                          print "      name: " name
                          if (mode == "rowfilter" || mode == "both") {
                              if (name == "date_dim")          print "      rowFilter: \"d_year <= 2002\""
                              if (name == "customer")          print "      rowFilter: \"c_birth_year >= 1930\""
                              if (name == "customer_address")  print "      rowFilter: \"ca_country = 'United States'\""
                          }
                          print "      columns:"
                      }
                      next }
intable && /^\)/   { intable = 0; next }
intable && /^[ \t]*[a-z_]/ {
                      line = $0
                      sub(/[ \t]*,?[ \t]*$/, "", line)          # 去行尾逗号
                      # 去掉 primary key / unique 等表级约束行（带缩进）
                      if (line ~ /^[ \t]*(primary key|unique|check|constraint|foreign key)/) next
                      # 列名在首 token，类型是余下整段（可能带空格: decimal(5, 2)、not null）
                      match(line, /[a-z_][a-z0-9_]*/)
                      col = substr(line, RSTART, RLENGTH)
                      typ = substr(line, RSTART + RLENGTH)
                      gsub(/^[ \t]+|[ \t]+$/, "", typ)
                      gsub(/[ \t]+not null$/, "", typ)
                      gsub(/[ \t]+null$/, "", typ)
                      gsub(/, +/, ",", typ)
                      if (typ == "") next
                      print "        - { name: " col ", type: \"" typ "\" }"
                      next }
END {
    if (mode == "mask" || mode == "both") {
        print ""
        print "# 列脱敏绑定（PIE 风格列 → 命名策略）"
        print "columns:"
        print "  - { catalog: tpcds, schema: public, table: customer, column: c_email_address, policy: mask_email }"
        print "  - { catalog: tpcds, schema: public, table: customer, column: c_phone, policy: mask_phone }"
        print "  - { catalog: tpcds, schema: public, table: customer, column: c_last_name, policy: mask_name }"
        print "  - { catalog: tpcds, schema: public, table: customer, column: c_first_name, policy: mask_name }"
        print "  - { catalog: tpcds, schema: public, table: customer, column: c_customer_id, policy: mask_hash }"
        print "  - { catalog: tpcds, schema: public, table: customer, column: c_birth_country, policy: mask_text }"
        print "  - { catalog: tpcds, schema: public, table: customer_address, column: ca_street_name, policy: mask_text }"
        print ""
        print "policies:"
        print "  mask_email:"
        print "    udf: mask_email"
        print "  mask_phone:"
        print "    udf: mask_phone"
        print "    arguments: [3, 4]"
        print "  mask_name:"
        print "    udf: mask_name"
        print "  mask_hash:"
        print "    udf: mask_hash"
        print "    arguments: ['sha256']"
        print "  mask_text:"
        print "    udf: mask_text"
    } else {
        print ""
        print "# 行过滤模式：无列脱敏绑定"
        print "columns: []"
        print "policies: {}"
    }
}
