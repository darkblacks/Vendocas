export async function api<T>(url:string, options?:RequestInit):Promise<T>{
  const response=await fetch(url,{headers:{'Content-Type':'application/json'},...options});
  if(!response.ok){const body=await response.json().catch(()=>({}));throw new Error(body.error||'Erro ao acessar o servidor local');}
  return response.json();
}
export const money=(value:number)=>new Intl.NumberFormat('pt-BR',{style:'currency',currency:'BRL'}).format(value||0);
export const brDate=(value:string)=>value?new Date(`${value}T12:00:00`).toLocaleDateString('pt-BR'):'—';
