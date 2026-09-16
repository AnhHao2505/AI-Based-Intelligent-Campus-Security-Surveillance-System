import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { X, Loader } from 'lucide-react';
import { createCamera } from '../../services/cameraService';
import Button from '../../components/ui/Button';
import Input from '../../components/ui/Input';
import '../../styles/CameraCreateModal.css';

const optionalNumber = z.preprocess((value) => value === '' ? undefined : Number(value), z.number().finite().optional());
const cameraSchema = z.object({
  name: z.string().trim().min(1, 'Tên camera là bắt buộc'),
  cameraCode: z.string().optional(),
  floor: z.preprocess((value) => value === '' ? undefined : Number(value), z.number().int().optional()),
  zoneName: z.string().optional(),
  x: optionalNumber,
  y: optionalNumber,
  mountingHeight: optionalNumber,
  orientation: optionalNumber,
  tiltAngle: optionalNumber,
});

export default function CameraCreateModal({ isOpen, onClose, onSuccess }) {
  const queryClient = useQueryClient();
  const { register, handleSubmit, reset, formState: { errors } } = useForm({
    resolver: zodResolver(cameraSchema),
    defaultValues: { cameraCode: '', name: '', floor: '', zoneName: '', x: '', y: '', mountingHeight: '', orientation: '', tiltAngle: '' },
  });
  const mutation = useMutation({
    mutationFn: createCamera,
    onSuccess: (response) => {
      queryClient.invalidateQueries({ queryKey: ['cameras'] });
      toast.success('Tạo camera thành công');
      reset();
      onSuccess?.(response);
      onClose();
    },
    onError: (err) => toast.error(err.message || 'Lỗi khi tạo camera.'),
  });

  if (!isOpen) return null;

  const loading = mutation.isPending;

  return (
    <div className="modal-overlay">
      <div className="modal-container">
        <div className="modal-header">
          <h2>Thêm Camera Mới</h2>
          <Button variant="ghost" className="modal-close" onClick={onClose} disabled={loading}>
            <X size={20} />
          </Button>
        </div>

        <form onSubmit={handleSubmit((values) => mutation.mutate({ ...values, cameraCode: values.cameraCode || undefined, zoneName: values.zoneName || undefined }))} className="modal-form">
          {errors.name && <div className="modal-error">{errors.name.message}</div>}

          <div className="form-grid">
            <div className="form-group col-span-2">
              <label htmlFor="name">Tên Camera <span className="required">*</span></label>
              <Input
                id="name"
                {...register('name')}
                placeholder="Ví dụ: Camera Cổng Chính A"
                disabled={loading}
              />
            </div>

            <div className="form-group">
              <label htmlFor="cameraCode">Mã Camera</label>
              <Input
                id="cameraCode"
                {...register('cameraCode')}
                placeholder="Tự động sinh nếu bỏ trống"
                disabled={loading}
              />
            </div>

            <div className="form-group">
              <label htmlFor="floor">Tầng</label>
              <Input
                type="number"
                id="floor"
                {...register('floor')}
                placeholder="Ví dụ: 1"
                disabled={loading}
              />
            </div>

            <div className="form-group">
              <label htmlFor="zoneName">Khu vực (Zone)</label>
              <Input
                type="text"
                id="zoneName"
                {...register('zoneName')}
                placeholder="Ví dụ: Sảnh tòa nhà"
                disabled={loading}
              />
            </div>

            <div className="form-group">
              <label htmlFor="mountingHeight">Độ cao lắp đặt (m)</label>
              <Input
                type="number"
                step="0.1"
                id="mountingHeight"
                {...register('mountingHeight')}
                placeholder="Ví dụ: 3.5"
                disabled={loading}
              />
            </div>

            <div className="form-group">
              <label htmlFor="x">Toạ độ X (Pixel)</label>
              <Input
                type="number"
                step="0.01"
                id="x"
                {...register('x')}
                placeholder="X trên bản đồ"
                disabled={loading}
              />
            </div>

            <div className="form-group">
              <label htmlFor="y">Toạ độ Y (Pixel)</label>
              <Input
                type="number"
                step="0.01"
                id="y"
                {...register('y')}
                placeholder="Y trên bản đồ"
                disabled={loading}
              />
            </div>

            <div className="form-group">
              <label htmlFor="orientation">Góc quay (độ)</label>
              <Input
                type="number"
                step="0.1"
                id="orientation"
                {...register('orientation')}
                placeholder="Ví dụ: 180"
                disabled={loading}
              />
            </div>

            <div className="form-group">
              <label htmlFor="tiltAngle">Góc nghiêng (độ)</label>
              <Input
                type="number"
                step="0.1"
                id="tiltAngle"
                {...register('tiltAngle')}
                placeholder="Ví dụ: -15"
                disabled={loading}
              />
            </div>
          </div>

          <div className="modal-actions">
            <Button type="button" variant="secondary" className="btn-secondary" onClick={onClose} disabled={loading}>
              Huỷ
            </Button>
            <Button type="submit" className="btn-primary" disabled={loading}>
              {loading ? <Loader className="animate-spin" size={16} /> : 'Tạo camera'}
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
}
