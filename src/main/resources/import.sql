-- insert admin (username a, password aa)
INSERT INTO IWUser (id, enabled, roles, username, password, src)
VALUES (1, TRUE, 'ADMIN,USER', 'a',
    '{bcrypt}$2a$10$2BpNTbrsarbHjNsUWgzfNubJqBRf.0Vz9924nRSHBqlbPKerkgX.W', 'https://aestheticwallpapers.org/wp-content/uploads/2021/07/1ab11a47cf160bfa05a493c20c0ed69e.jpg');
INSERT INTO IWUser (id, enabled, roles, username, password, src)
VALUES (2, TRUE, 'USER', 'b',
    '{bcrypt}$2a$10$2BpNTbrsarbHjNsUWgzfNubJqBRf.0Vz9924nRSHBqlbPKerkgX.W', 'https://i.pinimg.com/originals/cc/cd/42/cccd427cef4ccc0bd6a7ee5727be665f.png');
-- start id numbering from a value that is larger than any assigned above
ALTER SEQUENCE "PUBLIC"."GEN" RESTART WITH 1024;

INSERT INTO "PUBLIC"."RECIPE" VALUES
(1, 2, 'Pizza', 3.00, 'https://w6h5a5r4.rocketcdn.me/wp-content/uploads/2019/06/pizza-con-chorizo-jamon-y-queso-1080x671.jpg', NULL),
(2, 2, 'Hamburguesa', 2.50, 'https://okdiario.com/img/2021/05/28/hamburguesa-3-655x368.jpg', NULL),
(3, 2, 'Pasta Carbonara', 7.99, 'https://www.elespectador.com/resizer/VDIYcF2ol0HmQ3bC9SvoI7R23Es=/920x613/filters:format(jpeg)/cloudfront-us-east-1.images.arcpublishing.com/elespectador/TMTI6JW2CZETZOJTCUN3MQPHIY.jpg', NULL),
(4, 2, 'Pasta Boloñesa', 7.99, 'https://w6h5a5r4.rocketcdn.me/wp-content/uploads/2019/05/espaguetis-a-la-bolonesa-1080x671.jpg', NULL),
(5, 2, 'Perrito Caliente', 1.99, 'https://imag.bonviveur.com/perrito-caliente.jpg', NULL);