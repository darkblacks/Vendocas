export type Transaction = {id:number;date:string;type:'Entrada'|'Saída';amount:number;event:string;reason:string;description:string;recurring:number;periodicity:string;person:string};
export type Debt = {id:number;date:string;debtor:string;account:string;amount:number;reason:string;event:string;due_date:string;status:'Pendente'|'Pago'|'Vencido'|'Cancelado';payment_date:string;notes:string};
