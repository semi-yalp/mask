-- crm 库：脱敏 + 行过滤实测业务表
BEGIN;

CREATE TABLE customer (
  id         bigint PRIMARY KEY,
  name       varchar(50) NOT NULL,
  phone      varchar(20),
  email      varchar(100),
  id_card    varchar(18),
  status     varchar(20),
  region     varchar(20),
  manager_id bigint,
  created_at timestamp,
  address    varchar(200)
);

CREATE TABLE orders (
  id          bigint PRIMARY KEY,
  customer_id bigint,
  region      varchar(20),
  amount      numeric(10,2),
  created_at  timestamp
);

CREATE TABLE other (
  id  bigint PRIMARY KEY,
  tag varchar(50)
);

-- INSERT ... SELECT / CTAS 的写入目标
CREATE TABLE archive (
  id     bigint,
  phone  varchar(20),
  email  varchar(100),
  region varchar(20)
);

INSERT INTO customer
SELECT g,
  (ARRAY['张伟','李娜','王芳','刘强','陈静','杨洋','赵敏','黄磊','周杰','吴兰',
         '徐勇','孙丽','马超','朱婷','胡军','郭涛','何雨','高飞','林芳','罗成'])[1 + (g % 20)],
  CASE WHEN g % 19 = 0 THEN NULL
       ELSE '13' || lpad((((g::bigint * 862914517) % 1000000000))::text, 9, '0') END,
  CASE WHEN g % 17 = 0 THEN NULL
       ELSE 'user' || g || '@' || (ARRAY['example.com','test.org','corp.cn'])[1 + g % 3] END,
  CASE WHEN g % 23 = 0 THEN NULL
       ELSE '330102' || (1955 + g % 45)::text
            || lpad((1 + g % 12)::text, 2, '0') || lpad((1 + g % 28)::text, 2, '0')
            || lpad((g % 1000)::text, 3, '0') || (g % 10)::text END,
  CASE WHEN g % 13 = 0 THEN NULL WHEN g % 3 = 0 THEN 'inactive' ELSE 'active' END,
  CASE WHEN g % 11 = 0 THEN NULL
       ELSE (ARRAY['north','south','east','west'])[1 + g % 4] END,
  CASE WHEN g % 5 = 0 THEN NULL ELSE ((g % 30) + 1) END,
  TIMESTAMP '2023-01-01 08:00:00' + (g || ' days')::interval,
  '杭州市' || (ARRAY['西湖区','余杭区','拱墅区','滨江区','上城区'])[1 + g % 5]
  || (ARRAY['文一西路','文二西路','莫干山路','学院路','教工路'])[1 + g % 5]
  || (100 + g) || '号'
FROM generate_series(1, 50) g;

INSERT INTO orders
SELECT g,
  (g % 50) + 1,
  CASE WHEN g % 7 = 0 THEN NULL
       ELSE (ARRAY['north','south','east','west'])[1 + g % 4] END,
  round(((g::numeric * 13.37 + 9.9) % 9999.99), 2),
  TIMESTAMP '2024-01-01 00:00:00' + ((g * 3) || ' hours')::interval
FROM generate_series(1, 120) g;

INSERT INTO other
SELECT g, 'tag-' || g || '-' || (ARRAY['alpha','beta','gamma','delta'])[1 + g % 4]
FROM generate_series(1, 10) g;

COMMIT;
