# Reboot DNS VPN — PoC 0 (Android físico)

**ATENÇÃO:** Este código cria uma VPN com rota DNS virtual /32, mas **não encaminha nem responde consultas DNS**. Durante o teste, a resolução DNS pode falhar. Use aparelho de desenvolvimento e desative pela interface ou Ajustes > VPN. Não é filtro de conteúdo, nem produto pronto.

## Como abrir
1. Android Studio > Open > selecionar esta pasta. Necessário JDK 17, Android SDK Platform 35 e Gradle compatível.
2. Caso Android Studio solicite sincronização/ajuste de plugins, faça o sync. Este ZIP não contém wrapper Gradle nem APK compilado.
3. Ative opções de desenvolvedor e Depuração USB no aparelho de teste; conecte-o e execute o módulo `app`.
4. Toque em **Autorizar e ativar VPN de teste**, aceite o consentimento Android e observe status de VPN e `adb logcat -s RebootDnsPoc`.
5. Teste por pouco tempo e toque **Desativar VPN de teste**. Se necessário, desligue em Ajustes > Rede e Internet > VPN.

## Implementado
- Activity com ações de iniciar/parar.
- Consentimento via `VpnService.prepare()`.
- Serviço foreground com declaração systemExempted (validar elegibilidade VPN na versão/SDK do aparelho).
- TUN IPv4 10.111.0.1/32, DNS virtual 10.111.0.2 e rota 10.111.0.2/32.
- Loop simples que lê pacotes e somente contabiliza agregados. **Descarta todos os pacotes destinados ao TUN.**
- Fechamento do TUN no stop/revoke.

## Ainda NÃO implementado
- Parser IP/UDP/53, socket upstream protegido, encaminhamento de consultas, resposta pelo TUN, IPv6/TCP/53.
- Reconciliação persistida de estados, confirmação operacional DNS, sessões temporizadas, recuperação, notificações de falha.
- Testes instrumentados e validação de build em ambiente com Android SDK.

## Critério para próxima entrega
Evidenciar pacotes chegando no TUN em Android físico, implementar parsing UDP/53, encaminhamento via `protect(socket)` e construção de resposta IP/UDP. Não interpretar a presença de TUN como DNS funcional.
