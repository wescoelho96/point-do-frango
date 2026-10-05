package com.pointdofrango.financeiro;

/** Para onde foi o dinheiro. Vale para a saída da gaveta (caixa) e para o que é pago por fora. */
public enum CategoriaSaida {
    INSUMOS("Compra de insumos / mercado"),
    FUNCIONARIO("Diárias de funcionários"),
    SALARIO("Salários"),
    MOTOBOY("Motoboy"),
    PRO_LABORE("Meu salário (pró-labore)"),
    IMPOSTO("Impostos (DAS MEI / Simples)"),
    CONTA_FIXA("Contas fixas"),
    EQUIPAMENTO("Equipamentos e manutenção"),
    OUTRO("Outros"),
    /** Dinheiro tirado da gaveta para o cofre/banco: muda de lugar, não é gasto. */
    SANGRIA("Sangria (retirada)"),
    /** Dinheiro colocado na gaveta. */
    SUPRIMENTO("Suprimento");

    private final String rotulo;

    CategoriaSaida(String rotulo) {
        this.rotulo = rotulo;
    }

    public String getRotulo() {
        return rotulo;
    }

    public boolean gasto() {
        return this != SANGRIA && this != SUPRIMENTO;
    }

    public boolean pagaPessoa() {
        return this == FUNCIONARIO || this == SALARIO || this == MOTOBOY;
    }
}
