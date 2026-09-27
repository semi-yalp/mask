package io.sqlmask.mcp;

/** Test fixtures copied verbatim from mask-engine tests (same schema the kernel accepts). */
public final class Fixtures {

  /** metadata 无内嵌策略（policies: {}），配合 Ranger 式 policyYaml 用。 */
  public static final String METADATA = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            columns:
              - {name: id, type: bigint}
              - {name: phone, type: varchar}
              - {name: status, type: varchar}
      policies: {}
      """;

  public static final String MASK_POLICIES = """
      policies:
        - name: mask-phone
          resources:
            - {catalog: crm, schema: public, table: customer, column: phone}
          dataMaskItems:
            - {groups: ["*"], udf: mask_phone, arguments: [3, 4]}
      """;

  public static final String SQL = "SELECT phone FROM customer;";

  /** 内嵌 legacy 策略的 metadata，trino/pg/mysql 通用。 */
  public static final String TRINO_YAML = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            columns:
              - name: id
                type: bigint
              - name: phone
                type: varchar
              - name: email
                type: varchar
      columns:
        - catalog: crm
          schema: public
          table: customer
          column: phone
          policy: phone_mask
        - catalog: crm
          schema: public
          table: customer
          column: email
          policy: email_mask
      policies:
        phone_mask:
          udf: mask_phone
          arguments: [3, 4]
        email_mask:
          udf: mask_email
          arguments: []
      """;

  private Fixtures() {
  }
}
