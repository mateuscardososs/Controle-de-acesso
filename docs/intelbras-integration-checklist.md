# Checklist de integração Intelbras

Use este documento antes de implementar a integração real. A meta é transformar incerteza operacional em contrato técnico verificável.

## Identificação do equipamento

- Modelo exato do equipamento:
- Versão de firmware:
- Número de série:
- Local físico:
- IP fixo:
- Porta HTTP/HTTPS:
- Porta/API adicional:
- Usuário admin:
- Senha admin:
- Responsável local:

## Interface e capacidades

- Possui interface web? Sim/Não
- A interface web permite cadastro de pessoas? Sim/Não
- A interface web permite envio de face/foto? Sim/Não
- Possui API HTTP documentada? Sim/Não
- Possui SDK oficial? Sim/Não
- Possui webhook/callback de eventos? Sim/Não
- Possui exportação de logs/eventos? Sim/Não
- Exige digest auth, basic auth, token, sessão ou outro mecanismo?

## Endpoints disponíveis

Registrar aqui somente após validação contra documentação oficial ou inspeção do equipamento.

- Autenticação:
- Listar dispositivos/status:
- Cadastrar pessoa:
- Atualizar pessoa:
- Remover/bloquear pessoa:
- Enviar face:
- Atualizar permissão/horário/área:
- Receber eventos:
- Consultar eventos:
- Heartbeat/status:

## Cadastro de pessoa

- Campo identificador usado pelo equipamento:
- CPF é aceito diretamente? Sim/Não
- Matrícula/código externo é obrigatório? Sim/Não
- Campos mínimos:
- Limites de tamanho:
- Comportamento em duplicidade:
- Como validar sucesso:

## Envio de face

- Formato aceito: JPG/PNG/Base64/multipart/outro
- Tamanho máximo:
- Resolução recomendada:
- Exige cadastro prévio da pessoa? Sim/Não
- Retorna score/qualidade? Sim/Não
- Como validar sucesso:

## Remoção e bloqueio

- Remover pessoa exclui também a face? Sim/Não
- Bloqueio preserva histórico? Sim/Não
- Endpoint/ação para inativar:
- Endpoint/ação para remover:
- Como validar sucesso:

## Eventos de acesso

- Eventos chegam por webhook? Sim/Não
- Se webhook, o equipamento permite configurar URL? Sim/Não
- Se polling, intervalo seguro:
- Campos recebidos:
- Identificador de pessoa:
- Identificador do dispositivo:
- Resultado permitido/negado:
- Timestamp vem com timezone? Sim/Não
- Exemplo de payload bruto:

## Teste de comunicação

1. Confirmar ping/rede entre API e equipamento.
2. Confirmar acesso à interface web.
3. Validar autenticação da API/SDK.
4. Consultar status do dispositivo.
5. Cadastrar pessoa de teste.
6. Enviar face de teste.
7. Atribuir permissão/área.
8. Realizar acesso físico.
9. Receber ou consultar evento.
10. Bloquear/remover pessoa de teste.

## Riscos técnicos

- Firmware pode variar endpoints e payloads entre modelos.
- Autenticação pode depender de sessão/cookie e expirar sem aviso claro.
- Upload de face pode ter restrições de qualidade, tamanho e iluminação.
- Eventos podem chegar duplicados ou fora de ordem.
- Relógio do dispositivo pode estar fora de sincronia.
- Rede local pode bloquear callbacks do equipamento para a API.
- Sem documentação oficial, reverse engineering aumenta risco operacional.
- Operações em lote podem sobrecarregar o equipamento.
- Erros do equipamento podem ser códigos genéricos sem causa detalhada.
