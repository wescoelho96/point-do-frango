# Segurança

## Como reportar uma vulnerabilidade

Não abra issue pública. Use a aba **Security → Report a vulnerability** deste repositório
(relato privado do GitHub). A resposta inicial sai em até 5 dias úteis.

## O que o projeto já faz

- Nenhum segredo no código: banco, chave JWT e senha do administrador vêm de variáveis de ambiente.
- Senhas com BCrypt; 5 tentativas erradas bloqueiam o login por 15 minutos.
- Token JWT assinado (HS256) com validade de 12 h, revogado na hora ao trocar a senha, desativar o usuário ou mudar o perfil.
- Autorização por perfil no servidor; custos e dados de clientes só para o administrador.
- Cabeçalhos: Content-Security-Policy sem script inline, HSTS, X-Frame-Options, Referrer-Policy,
  Permissions-Policy e Cross-Origin-Opener-Policy.
- Endpoints públicos (cardápio online) sem dado interno, com preço vindo do banco e limite de pedidos.
- Leitura de XML protegida contra XXE; CSV exportado protegido contra injeção de fórmula.
- Banco no Supabase com RLS em todas as tabelas, bloqueando a API REST pública.
- LGPD: dados mínimos do cliente, exclusão a pedido e retenção automática de 12 meses.
- Dependências monitoradas pelo Dependabot e código analisado pelo CodeQL a cada push.
